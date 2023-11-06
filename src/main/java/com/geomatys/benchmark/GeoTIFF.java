/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. You may not use this
 * file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.geomatys.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.awt.image.Raster;
import java.awt.geom.AffineTransform;
import org.apache.sis.setup.OptionKey;
import org.apache.sis.storage.DataStoreException;
import org.apache.sis.storage.GridCoverageResource;
import org.apache.sis.storage.StorageConnector;
import org.apache.sis.storage.geotiff.Compression;
import org.apache.sis.storage.geotiff.GeoTiffStore;
import org.apache.sis.coverage.grid.GridCoverage;
import org.apache.sis.coverage.grid.j2d.ColorModelFactory;
import org.apache.sis.coverage.grid.j2d.TiledImage;


/**
 * Reads a large GeoTIFF files and rewrites it in a temporary file, with execution time measurements.
 * This benchmarks contains hard-coded path to files that need to be modified before execution.
 *
 * @author Martin Desruisseaux
 */
public class GeoTIFF {
    /**
     * Path to the GeoTIFF file to read. Edit as needed for your local environment.
     */
    private static final String SOURCE_FILE = "/tmp/SIS/input.tiff";

    /**
     * Path to the GeoTIFF file to write. Edit as needed for your local environment.
     */
    private static final String TARGET_FILE = "/tmp/SIS/output.tiff";

    /**
     * Number of times to repeat the operation.
     * The first iteration will be ignored as warmup time.
     */
    private static final int NUM_TRIES = 10;

    /**
     * Whether to apply compression at writing time.
     */
    private static final boolean COMPRESS = true;

    /**
     * The TIFF predictor to apply before compression.
     * 1 = no predictor, 2 = horizontal differentiating.
     */
    private static final int PREDICTOR = 1;

    /**
     * The library to test.
     * 0 = GDAL info (for measuring GDAL startup time),
     * 1 = GDAL,
     * 2 = Image I/O,
     * 3 = Apache SIS,
     */
    private static final int CASE = 3;

    /**
     * Runs the benchmarks with hard-coded path.
     *
     * @param  args  ignored.
     * @throws Exception if any error occurred.
     */
    public static void main(String[] args) throws Exception {
        final var test = new GeoTIFF();
        for (int i=0; i<NUM_TRIES; i++) {
            long t = System.nanoTime();
            switch (CASE) {
                case 0: test.gdalinfo();     break;
                case 1: test.withGDAL();     break;
                case 2: test.withImageIO();  break;
                case 3: test.withSIS(false); break;
            }
            t = System.nanoTime() - t;
            final long length;
            if (CASE == 0) {
                length = 0;
            } else {
                length = Files.size(test.target);
            }
            System.out.println("File size = " + length + ", time = " + t / 1E9f);
        }
    }

    /**
     * The {@link #SOURCE_FILE} and {@link #TARGET_FILE} paths.
     */
    private final Path source, target;

    /**
     * Creates a new benchmark for GeoTIFF.
     */
    private GeoTIFF() {
        source = Path.of(SOURCE_FILE);
        target = Path.of(TARGET_FILE);
    }

    /**
     * Executes {@code gdalinfo} on the source file.
     * No target file is created by this test.
     */
    private void gdalinfo() throws IOException, InterruptedException {
        int code = new ProcessBuilder("gdalinfo", source.toString()).start().waitFor();
        if (code != 0) {
            System.err.println("Error code " + code);
        }
    }

    /**
     * Reads and writes the image with {@code gdal_translate}.
     */
    private void withGDAL() throws IOException, InterruptedException {
        int code = new ProcessBuilder("gdal_translate",
                "-co", "COMPRESS="  + (COMPRESS ? "DEFLATE" : "NONE"),
                "-co", "PREDICTOR=" + (COMPRESS ? PREDICTOR : 1),
                "-co", "BLOCKYSIZE=8",
                source.toString(), target.toString()).start().waitFor();
        if (code != 0) {
            System.err.println("Error code " + code);
        }
    }

    /**
     * Reads and writes the image with Java I/O.
     */
    private void withImageIO() throws IOException {
        BufferedImage image = ImageIO.read(source.toFile());
        if (COMPRESS) try (ImageOutputStream output = ImageIO.createImageOutputStream(target.toFile())) {
            final ImageWriter writer = ImageIO.getImageWritersByFormatName("TIFF").next();
            final ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionType("ZLib");
            params.setCompressionQuality(1);
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), params);
            writer.dispose();
        } else {
            ImageIO.write(image, "TIFF", target.toFile());
        }
    }

    /**
     * Reads and writes the image with Apache SIS. The image can optionally be
     * reformatted before writing in order to use the same tile size than other tests.
     *
     * @param  reformat  whether to reformat the image before to write it.
     */
    private void withSIS(final boolean reformat) throws DataStoreException {
        GridCoverage coverage;
        var c = new StorageConnector(source);
        try (GeoTiffStore ds = new GeoTiffStore(null, c)) {
            GridCoverageResource r = ds.components().iterator().next();
            coverage = r.read(null, null);
        }
        c = new StorageConnector(target);
        c.setOption(OptionKey.OPEN_OPTIONS, new OpenOption[] {
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        });
        c.setOption(Compression.OPTION_KEY, COMPRESS ? Compression.DEFLATE.withPredictor(PREDICTOR) : Compression.NONE);
        try (GeoTiffStore ds = new GeoTiffStore(null, c)) {
            if (reformat) {
                ds.append(changeTileHeight(toPixelInterleave(coverage.render(null))), coverage.getGridGeometry(), null);
            } else {
                ds.append(coverage, null);
            }
        }
    }

    /**
     * Replace the band interleave sample model by pixel interleave sample model.
     *
     * @param  source  the image to reformat.
     * @return the reformatted image.
     */
    private static RenderedImage toPixelInterleave(final RenderedImage source) {
        var cm = ColorModelFactory.createRGB(8, false, false);
        var sm = cm.createCompatibleSampleModel(source.getWidth(), source.getHeight());
        final var target = new BufferedImage(cm, Raster.createWritableRaster(sm, null), false, null);
        final var g = target.createGraphics();
        g.drawRenderedImage(source, new AffineTransform());
        g.dispose();
        return target;
    }

    /**
     * Layouts the given image with a tile height smaller than the original tile height.
     * The new tile height must be a divisor of the original tile height.
     * This method is used for replacing, for example, strips of 128 pixel height by strips of 8 pixel height.
     * The intend is to have strips of the same size than the strips produced by Image I/O in order to compare.
     *
     * @param  source  the image to reformat.
     * @return the reformatted image.
     */
    private static RenderedImage changeTileHeight(final RenderedImage source) {
        if ((source.getMinX() | source.getMinY() | source.getMinTileX() | source.getMinTileY()) != 0) {
            throw new IllegalArgumentException("Unsupported tile matrix origin.");
        }
        final int tileHeight       = 8;
        final int tileWidth        = source.getTileWidth();
        final int sourceTileHeight = source.getTileHeight();
        if (sourceTileHeight % tileHeight != 0 || tileWidth != source.getWidth()) {
            throw new IllegalArgumentException("Unsupported tile size.");
        }
        final Raster[] tiles = new Raster[source.getHeight() / tileHeight];
        for (int i=0; i<tiles.length; i++) {
            final int y = i * tileHeight;
            final Raster tile = source.getTile(0, y / sourceTileHeight);
            tiles[i] = tile.createChild(0, y, tileWidth, tileHeight, 0, y, null);
        }
        return new TiledImage(null, source.getColorModel(), tileWidth, tileHeight * tiles.length, 0, 0, tiles);
    }
}
