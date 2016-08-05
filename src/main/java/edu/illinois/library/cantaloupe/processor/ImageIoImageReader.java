package edu.illinois.library.cantaloupe.processor;

import edu.illinois.library.cantaloupe.image.Crop;
import edu.illinois.library.cantaloupe.image.Format;
import edu.illinois.library.cantaloupe.image.Operation;
import edu.illinois.library.cantaloupe.image.OperationList;
import edu.illinois.library.cantaloupe.image.Scale;
import edu.illinois.library.cantaloupe.resolver.StreamSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * <p>Image reader wrapping an ImageIO {@link ImageReader} instance, with
 * enhancements to support efficient reading of multi-resolution and/or tiled
 * source images with scale-appropriate subsampling.</p>
 *
 * <p>Clients should remember to call {@link #dispose()} when done with an
 * instance.</p>
 */
class ImageIoImageReader {

    // Note: methods that return BufferedImages (for Java 2D) are arranged
    // toward the beginning of the class; methods that return RenderedImages
    // (for JAI) are toward the end.

    public enum ReaderHint {
        ALREADY_CROPPED
    }

    private static Logger logger = LoggerFactory.
            getLogger(ImageIoImageReader.class);

    /** Set in setSource(). */
    private ImageInputStream inputStream;

    /** Assigned by createReader() if inputStream is not null. */
    private ImageReader reader;

    /** Set in setFormat() */
    private Format format;

    /** Set in setSource() */
    private Object source;

    static {
        ImageIO.setUseCache(false);
    }

    /**
     * @return Map of available output formats for all known source formats,
     * based on information reported by ImageIO.
     */
    public static Set<Format> supportedFormats() {
        return new HashSet<>(Arrays.asList(Format.BMP, Format.GIF, Format.JPG,
                Format.PNG, Format.TIF));
    }

    /**
     * No-op constructor. Clients must set a source with {@link #setSource}
     * before the instance will be usable.
     */
    public ImageIoImageReader() {}

    /**
     * <p>Initializes an instance. The image's magic number will be read to
     * infer its type.</p>
     *
     * <p>{@link #ImageIoImageReader(File, Format)} is more efficient and
     * preferred.</p>
     *
     * @param inputFile Image file to read.
     * @throws IOException
     */
    public ImageIoImageReader(File inputFile) throws IOException {
        setSource(inputFile);
    }

    /**
     * Initializes an instance.
     *
     * @param inputFile Image file to read.
     * @throws IOException
     */
    public ImageIoImageReader(File inputFile, Format format)
            throws IOException {
        setSource(inputFile);
        setFormat(format);
    }

    /**
     * <p>Initializes an instance. The image's magic number will be read to
     * infer its type.</p>
     *
     * <p>{@link #ImageIoImageReader(StreamSource, Format)} is more
     * efficient and preferred.</p>
     *
     * @param streamSource Source of streams to read.
     * @throws IOException
     */
    public ImageIoImageReader(StreamSource streamSource) throws IOException {
        setSource(streamSource);
    }

    /**
     * Initializes an instance.
     *
     * @param streamSource Source of streams to read.
     * @throws IOException
     */
    public ImageIoImageReader(StreamSource streamSource,
                              Format format) throws IOException {
        setSource(streamSource);
        setFormat(format);
    }

    private void createReader() throws IOException { // TODO: consider disallowing use without source format set
        if (inputStream == null) {
            throw new IOException("No source set.");
        }

        Iterator<ImageReader> it;
        if (format != null) {
            it = ImageIO.getImageReadersByMIMEType(
                    format.getPreferredMediaType().toString());
        } else {
            it = ImageIO.getImageReaders(inputStream);
        }

        if (format != null && format.equals(Format.TIF)) {
            while (it.hasNext()) {
                reader = it.next();
                // This version contains improvements over the Sun version,
                // namely support for BigTIFF.
                if (reader instanceof it.geosolutions.imageioimpl.plugins.tiff.TIFFImageReader) {
                    break;
                }
            }
        } else if (it.hasNext()) {
            reader = it.next();
        }

        if (reader != null) {
            reader.setInput(inputStream);
            logger.info("createReader(): using {}", reader.getClass().getName());
        } else {
            throw new IOException("Unable to determine the format of the " +
                    "source image.");
        }
    }

    /**
     * Should be called when the reader is no longer needed.
     *
     * @throws IOException
     */
    public void dispose() throws IOException {
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } finally {
            if (reader != null) {
                reader.dispose();
                reader = null;
            }
        }
    }

    public Node getMetadata(int imageIndex) throws IOException {
        if (reader == null) {
            createReader();
        }
        String metadataFormat = reader.getImageMetadata(imageIndex).
                getNativeMetadataFormatName();
        return reader.getImageMetadata(imageIndex).getAsTree(metadataFormat);
    }

    /**
     * @return The number of images contained inside the source image.
     * @throws IOException
     */
    public int getNumResolutions() throws IOException {
        if (reader == null) {
            createReader();
        }
        // The boolean parameter tells getNumImages() whether to scan for
        // images, which seems to be necessary for some, but is slower.
        int numImages = reader.getNumImages(false);
        if (numImages == -1) {
            numImages = reader.getNumImages(true);
        }
        return numImages;
    }

    /**
     * Gets the dimensions of the source image.
     *
     * @return Dimensions in pixels
     * @throws IOException
     */
    public Dimension getSize() throws IOException {
        if (reader == null) {
            createReader();
        }
        return getSize(reader.getMinIndex());
    }

    /**
     * Gets the dimensions of the image at the given index.
     *
     * @param imageIndex
     * @return Dimensions in pixels
     * @throws IOException
     */
    public Dimension getSize(int imageIndex) throws IOException {
        if (reader == null) {
            createReader();
        }
        final int width = reader.getWidth(imageIndex);
        final int height = reader.getHeight(imageIndex);
        return new Dimension(width, height);
    }

    /**
     * @param imageIndex
     * @return Tile size of the image at the given index. If the image is not
     *         tiled, the full image dimensions are returned.
     * @throws IOException
     */
    public Dimension getTileSize(int imageIndex) throws IOException {
        if (reader == null) {
            createReader();
        }
        final int width = reader.getTileWidth(imageIndex);
        final int height = reader.getTileHeight(imageIndex);
        return new Dimension(width, height);
    }

    private void reset() throws IOException {
        if (source instanceof File) {
            setSource((File) source);
        } else {
            setSource((StreamSource) source);
        }
        createReader();
    }

    public void setSource(File inputFile) throws IOException {
        dispose();
        source = inputFile;
        inputStream = new FileImageInputStream(inputFile);
    }

    public void setSource(StreamSource streamSource) throws IOException {
        dispose();
        source = streamSource;
        inputStream = streamSource.newImageInputStream();
    }

    public void setFormat(Format format) {
        this.format = format;
    }

    ////////////////////////////////////////////////////////////////////////
    /////////////////////// BufferedImage methods //////////////////////////
    ////////////////////////////////////////////////////////////////////////

    /**
     * Expedient but not necessarily efficient method wrapping
     * {@link ImageIO#read} that reads a whole image (excluding subimages) in
     * one shot.
     *
     * @return BufferedImage guaranteed to not be of type
     *         {@link BufferedImage#TYPE_CUSTOM}.
     * @throws IOException
     */
    public BufferedImage read() throws IOException {
        final BufferedImage image = ImageIO.read(inputStream);
        final BufferedImage rgbImage = Java2dUtil.convertCustomToRgb(image);
        if (rgbImage != image) {
            logger.warn("Converted image to RGB (this is very expensive)");
        }
        return rgbImage;
    }

    /**
     * <p>Attempts to read an image as efficiently as possible, utilizing its
     * tile layout and/or subimages, if possible.</p>
     *
     * <p>After reading, clients should check the reader hints to see whether
     * the returned image will require cropping.</p>
     *
     * @param ops
     * @param reductionFactor {@link ReductionFactor#factor} property will be
     *                        modified to reflect the reduction factor of the
     *                        returned image.
     * @param hints           Will be populated by information returned from
     *                        the reader.
     * @return BufferedImage best matching the given parameters, guaranteed to
     *         not be of {@link BufferedImage#TYPE_CUSTOM}. Clients should
     *         check the hints set to see whether they need to perform
     *         additional cropping.
     * @throws IOException
     * @throws ProcessorException
     */
    public BufferedImage read(final OperationList ops,
                              final ReductionFactor reductionFactor,
                              final Set<ReaderHint> hints)
            throws IOException, ProcessorException {
        if (reader == null) {
            createReader();
        }
        BufferedImage image = null;
        try {
            switch (reader.getFormatName().substring(0, 3).toUpperCase()) {
                case "TIF":
                    Crop crop = new Crop();
                    crop.setFull(true);
                    Scale scale = new Scale();
                    scale.setMode(Scale.Mode.FULL);
                    for (Operation op : ops) {
                        if (op instanceof Crop) {
                            crop = (Crop) op;
                        } else if (op instanceof Scale) {
                            scale = (Scale) op;
                        }
                    }
                    image = readSmallestUsableSubimage(crop, scale,
                            reductionFactor, hints);
                    break;
                // This is similar to the TIF case, except it doesn't scan for
                // subimages, which is costly to do.
                default:
                    crop = null;
                    scale = new Scale();
                    scale.setMode(Scale.Mode.FULL);
                    for (Operation op : ops) {
                        if (op instanceof Crop) {
                            crop = (Crop) op;
                        } else if (op instanceof Scale) {
                            scale = (Scale) op;
                        }
                    }
                    if (crop != null) {
                        final Dimension fullSize = new Dimension(
                                reader.getWidth(0), reader.getHeight(0));
                        image = tileAwareRead(0, crop.getRectangle(fullSize),
                                scale, reductionFactor, hints);
                    } else {
                        image = reader.read(0);
                    }
                    break;
            }
        } finally {
            reader.dispose();
        }
        if (image == null) {
            throw new UnsupportedSourceFormatException(reader.getFormatName());
        }
        BufferedImage rgbImage = Java2dUtil.convertCustomToRgb(image);
        if (rgbImage != image) {
            logger.warn("Converted {} to RGB (this is very expensive)",
                    ops.getIdentifier());
        }
        return rgbImage;
    }

    /**
     * Reads the smallest image that can fulfill the given crop and scale from
     * a multi-resolution image.
     *
     * @param crop   Requested crop
     * @param scale  Requested scale
     * @param rf     {@link ReductionFactor#factor} will be set to the reduction
     *               factor of the returned image.
     * @param hints  Will be populated by information returned by the reader.
     * @return The smallest image fitting the requested crop and scale
     * operations from the given reader.
     * @throws IOException
     */
    private BufferedImage readSmallestUsableSubimage(final Crop crop,
                                                     final Scale scale,
                                                     final ReductionFactor rf,
                                                     final Set<ReaderHint> hints)
            throws IOException {
        final Dimension fullSize = new Dimension(
                reader.getWidth(0), reader.getHeight(0));
        final Rectangle regionRect = crop.getRectangle(fullSize);
        final ImageReadParam param = reader.getDefaultReadParam();
        BufferedImage bestImage = null;
        if (scale.isNoOp()) {
            bestImage = tileAwareRead(0, regionRect, scale, rf, hints);
            logger.debug("readSmallestUsableSubimage(): using a {}x{} source " +
                    "image (0x reduction factor)",
                    bestImage.getWidth(), bestImage.getHeight());
        } else {
            // Pyramidal TIFFs will have > 1 image, each with half the
            // dimensions of the previous one. The boolean parameter tells
            // getNumImages() whether to scan for images, which seems to be
            // necessary for at least some files, but is slower. If it is
            // false, and getNumImages() can't find anything, it will return -1.
            int numImages = reader.getNumImages(false);
            if (numImages > 1) {
                logger.debug("readSmallestUsableSubimage(): " +
                        "detected {} subimage(s)", numImages);
            } else if (numImages == -1) {
                numImages = reader.getNumImages(true);
                if (numImages > 1) {
                    logger.debug("readSmallestUsableSubimage(): " +
                            "scan revealed {} subimage(s)", numImages);
                }
            }
            // At this point, we know how many images are available.
            if (numImages == 1) {
                bestImage = tileAwareRead(0, regionRect, scale, rf, hints);
                logger.debug("readSmallestUsableSubimage(): using a {}x{} " +
                        "source image (0x reduction factor)",
                        bestImage.getWidth(), bestImage.getHeight());
            } else if (numImages > 1) {
                // Loop through the reduced images from smallest to largest to
                // find the first one that can supply the requested scale
                for (int i = numImages - 1; i >= 0; i--) {
                    final int subimageWidth = reader.getWidth(i);
                    final int subimageHeight = reader.getHeight(i);

                    final double reducedScale = (double) subimageWidth /
                            (double) fullSize.width;
                    boolean fits = false;
                    if (scale.getPercent() != null) {
                        fits = (scale.getPercent() <= reducedScale);
                    } else if (scale.getMode() == Scale.Mode.ASPECT_FIT_WIDTH) {
                        fits = (scale.getWidth() / (double) regionRect.width <= reducedScale);
                    } else if (scale.getMode() == Scale.Mode.ASPECT_FIT_HEIGHT) {
                        fits = (scale.getHeight() / (double) regionRect.height <= reducedScale);
                    } else if (scale.getMode() == Scale.Mode.ASPECT_FIT_INSIDE) {
                        fits = (scale.getWidth() / (double) regionRect.width <= reducedScale &&
                                scale.getHeight() / (double) regionRect.height <= reducedScale);
                    } else if (scale.getMode() == Scale.Mode.NON_ASPECT_FILL) {
                        fits = (scale.getWidth() / (double) regionRect.width <= reducedScale &&
                                scale.getHeight() / (double) regionRect.height <= reducedScale);
                    }
                    if (fits) {
                        rf.factor = ReductionFactor.
                                forScale(reducedScale, 0).factor;
                        logger.debug("readSmallestUsableSubimage(): " +
                                "subimage {}: {}x{} - fits! " +
                                "({}x reduction factor)",
                                i + 1, subimageWidth, subimageHeight,
                                rf.factor);
                        final Rectangle reducedRect = new Rectangle(
                                (int) Math.round(regionRect.x * reducedScale),
                                (int) Math.round(regionRect.y * reducedScale),
                                (int) Math.round(regionRect.width * reducedScale),
                                (int) Math.round(regionRect.height * reducedScale));
                        bestImage = tileAwareRead(i, reducedRect, scale, rf,
                                hints);
                        break;
                    } else {
                        logger.debug("readSmallestUsableSubimage(): " +
                                "subimage {}: {}x{} - too small",
                                i + 1, subimageWidth, subimageHeight);
                    }
                }
            }
        }
        return bestImage;
    }

    /**
     * <p>Returns an image for the requested source area by reading the tiles
     * (or strips) of the source image and joining them into a single image.
     * Subsampling will be used if possible.</p>
     *
     * <p>This method is intended to be compatible with all source images, no
     * matter the data layout (tiled, striped, etc.).</p>
     *
     * <p>This method may populate <code>hints</code> with
     * {@link ReaderHint#ALREADY_CROPPED}, in which case cropping will have
     * already been performed according to the
     * <code>requestedSourceArea</code> parameter.</p>
     *
     * @param imageIndex   Index of the image to read from the ImageReader.
     * @param region       Image region to retrieve. The returned image will be
     *                     this size or smaller if it would overlap the right
     *                     or bottom edge of the source image.
     * @param scale        Scale that is to be applied to the returned
     *                     image. Will be used to calculate a subsampling
     *                     rate.
     * @param subimageRf   Already-applied reduction factor from reading a
     *                     subimage, to which a subsampling-related reduction
     *                     factor may be added.
     * @param hints        Will be populated with information returned from the
     *                     reader.
     * @return Image
     * @throws IOException
     */
    private BufferedImage tileAwareRead(final int imageIndex,
                                        final Rectangle region,
                                        final Scale scale,
                                        final ReductionFactor subimageRf,
                                        final Set<ReaderHint> hints)
            throws IOException {
        final Dimension imageSize = new Dimension(
                reader.getWidth(imageIndex),
                reader.getHeight(imageIndex));
        // xScale and yScale are the percentages of the image axes needed
        // based on the given region and scale. If either are less than 0.5,
        // subsampling can be used for better efficiency.
        double xScale, yScale;
        if (scale.getPercent() != null) {
            xScale = (scale.getPercent() / subimageRf.getScale()) *
                    (region.width / (double) imageSize.width);
            yScale = (scale.getPercent() / subimageRf.getScale()) *
                    (region.height / (double) imageSize.height);
        } else {
            switch (scale.getMode()) {
                case FULL:
                    xScale = yScale = 1f;
                    break;
                case ASPECT_FIT_WIDTH:
                    xScale = yScale = scale.getWidth() / (double) region.width;
                    break;
                case ASPECT_FIT_HEIGHT:
                    xScale = yScale = scale.getHeight() / (double) region.height;
                    break;
                default:
                    xScale = scale.getWidth() / (double) region.width;
                    yScale = scale.getHeight() / (double) region.height;
                    break;
            }
        }

        logger.debug("tileAwareRead(): acquiring region {},{}/{}x{} from {}x{} image",
                region.x, region.y, region.width, region.height,
                imageSize.width, imageSize.height);

        final ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(region);

        final int subsampleReductionFactor = ReductionFactor.
                forScale(Math.max(xScale, yScale), 0).factor;

        logger.debug("tileAwareRead(): using a subsampling factor of {}",
                subsampleReductionFactor);

        subimageRf.factor += subsampleReductionFactor;
        if (subsampleReductionFactor > 0) {
            // Determine the number of rows/columns to skip between pixels.
            int subsample = 0;
            for (int i = 0; i <= subsampleReductionFactor; i++) {
                subsample = (subsample == 0) ? subsample + 1 : subsample * 2;
            }
            param.setSourceSubsampling(subsample, subsample, 0, 0);
        }
        hints.add(ReaderHint.ALREADY_CROPPED);
        try {
            return reader.read(imageIndex, param);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("Numbers of source Raster bands " +
                    "and source color space components do not match")) {
                /*
                This probably means that the embedded ICC profile is
                incompatible with the source image data.
                (JPEGImageReader is not very lenient.)
                See: https://github.com/medusa-project/cantaloupe/issues/41

                To deal with this situation, we will try reading again,
                ignoring the color profile. We need to reset the reader, and
                then read into a grayscale BufferedImage.
                */
                reset();

                // Credit: http://stackoverflow.com/a/11571181
                final Iterator<ImageTypeSpecifier> imageTypes = reader.getImageTypes(0);
                while (imageTypes.hasNext()) {
                    final ImageTypeSpecifier imageTypeSpecifier = imageTypes.next();
                    final int bufferedImageType = imageTypeSpecifier.getBufferedImageType();
                    if (bufferedImageType == BufferedImage.TYPE_BYTE_GRAY) {
                        param.setDestinationType(imageTypeSpecifier);
                        break;
                    }
                }

                return reader.read(imageIndex, param);
            } else {
                throw e;
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////
    /////////////////////// RenderedImage methods //////////////////////////
    ////////////////////////////////////////////////////////////////////////

    /**
     * Reads an image (excluding subimages).
     *
     * @return RenderedImage
     * @throws IOException
     * @throws UnsupportedSourceFormatException
     */
    public RenderedImage readRendered() throws IOException,
            UnsupportedSourceFormatException {
        if (reader == null) {
            createReader();
        }
        return reader.readAsRenderedImage(0, reader.getDefaultReadParam());
    }

    /**
     * <p>Attempts to reads an image as efficiently as possible, utilizing its
     * tile layout and/or subimages, if possible.</p>
     *
     * @param ops
     * @param reductionFactor {@link ReductionFactor#factor} property will be
     *                        modified to reflect the reduction factor of the
     *                        returned image.
     * @return RenderedImage best matching the given parameters.
     * @throws IOException
     * @throws ProcessorException
     */
    public RenderedImage readRendered(final OperationList ops,
                                      final ReductionFactor reductionFactor)
            throws IOException, ProcessorException {
        if (reader == null) {
            createReader();
        }
        RenderedImage image = null;
        try {
            switch (reader.getFormatName().substring(0, 3).toUpperCase()) {
                case "TIF":
                    Crop crop = new Crop();
                    crop.setFull(true);
                    Scale scale = new Scale();
                    scale.setMode(Scale.Mode.FULL);
                    for (Operation op : ops) {
                        if (op instanceof Crop) {
                            crop = (Crop) op;
                        } else if (op instanceof Scale) {
                            scale = (Scale) op;
                        }
                    }
                    image = readSmallestUsableSubimage(crop, scale,
                            reductionFactor);
                    break;
                // This is similar to the TIF case, except it doesn't scan for
                // subimages, which is costly to do.
                default:
                    crop = null;
                    for (Operation op : ops) {
                        if (op instanceof Crop) {
                            crop = (Crop) op;
                            break;
                        }
                    }
                    if (crop != null) {
                        image = reader.readAsRenderedImage(0,
                                reader.getDefaultReadParam());
                    } else {
                        image = reader.read(0);
                    }
                    break;
            }
        } finally {
            reader.dispose();
        }
        if (image == null) {
            throw new UnsupportedSourceFormatException(reader.getFormatName());
        }
        return image;
    }

    /**
     * Reads the smallest image that can fulfill the given crop and scale from
     * a multi-resolution image.
     *
     * @param crop   Requested crop
     * @param scale  Requested scale
     * @param rf     {@link ReductionFactor#factor} will be set to the reduction
     *               factor of the returned image.
     * @return The smallest image fitting the requested crop and scale
     * operations from the given reader.
     * @throws IOException
     */
    private RenderedImage readSmallestUsableSubimage(final Crop crop,
                                                     final Scale scale,
                                                     final ReductionFactor rf)
            throws IOException {
        final Dimension fullSize = new Dimension(
                reader.getWidth(0), reader.getHeight(0));
        final Rectangle regionRect = crop.getRectangle(fullSize);
        final ImageReadParam param = reader.getDefaultReadParam();
        RenderedImage bestImage = null;
        if (scale.isNoOp()) {
            bestImage = reader.readAsRenderedImage(0, param);
            logger.debug("readSmallestUsableSubimage(): using a {}x{} " +
                    "source image (0x reduction factor)",
                    bestImage.getWidth(), bestImage.getHeight());
        } else {
            // Pyramidal TIFFs will have > 1 image, each half the dimensions of
            // the next larger. The "true" parameter tells getNumImages() to
            // scan for images, which seems to be necessary for at least some
            // files, but is slower.
            int numImages = reader.getNumImages(false);
            if (numImages > 1) {
                logger.debug("readSmallestUsableSubimage(): detected {} " +
                        "subimage(s)", numImages - 1);
            } else if (numImages == -1) {
                numImages = reader.getNumImages(true);
                if (numImages > 1) {
                    logger.debug("readSmallestUsableSubimage(): " +
                            "scan revealed {} subimage(s)", numImages - 1);
                }
            }
            if (numImages == 1) {
                bestImage = reader.read(0, param);
                logger.debug("readSmallestUsableSubimage(): using a {}x{} " +
                        "source image (0x reduction factor)",
                        bestImage.getWidth(), bestImage.getHeight());
            } else if (numImages > 1) {
                // Loop through the reduced images from smallest to largest to
                // find the first one that can supply the requested scale
                for (int i = numImages - 1; i >= 0; i--) {
                    final int subimageWidth = reader.getWidth(i);
                    final int subimageHeight = reader.getHeight(i);

                    final double reducedScale = (double) subimageWidth /
                            (double) fullSize.width;
                    boolean fits = false;
                    if (scale.getPercent() != null) {
                        fits = (scale.getPercent() <= reducedScale);
                    } else if (scale.getMode() == Scale.Mode.ASPECT_FIT_WIDTH) {
                        fits = (scale.getWidth() / (double) regionRect.width <= reducedScale);
                    } else if (scale.getMode() == Scale.Mode.ASPECT_FIT_HEIGHT) {
                        fits = (scale.getHeight() / (double) regionRect.height <= reducedScale);
                    } else if (scale.getMode() == Scale.Mode.ASPECT_FIT_INSIDE) {
                        fits = (scale.getWidth() / (double) regionRect.width <= reducedScale &&
                                scale.getHeight() / (double) regionRect.height <= reducedScale);
                    } else if (scale.getMode() == Scale.Mode.NON_ASPECT_FILL) {
                        fits = (scale.getWidth() / (double) regionRect.width <= reducedScale &&
                                scale.getHeight() / (double) regionRect.height <= reducedScale);
                    }
                    if (fits) {
                        rf.factor = ReductionFactor.forScale(reducedScale, 0).factor;
                        logger.debug("readSmallestUsableSubimage(): " +
                                        "subimage {}: {}x{} - fits! " +
                                        "({}x reduction factor)",
                                i + 1, subimageWidth, subimageHeight,
                                rf.factor);
                        bestImage = reader.readAsRenderedImage(i, param);
                        break;
                    } else {
                        logger.debug("readSmallestUsableSubimage(): " +
                                        "subimage {}: {}x{} - too small",
                                i + 1, subimageWidth, subimageHeight);
                    }
                }
            }
        }
        return bestImage;
    }

}
