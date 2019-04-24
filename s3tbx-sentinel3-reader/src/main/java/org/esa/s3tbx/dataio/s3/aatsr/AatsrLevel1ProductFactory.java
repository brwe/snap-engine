package org.esa.s3tbx.dataio.s3.aatsr;

import org.esa.s3tbx.dataio.s3.Manifest;
import org.esa.s3tbx.dataio.s3.slstr.SlstrLevel1ProductFactory;
import org.esa.s3tbx.dataio.s3.util.MetTxReader;
import org.esa.s3tbx.dataio.s3.util.S3NetcdfReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.TiePointGeoCoding;
import org.esa.snap.core.datamodel.TiePointGrid;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * @author Sabine Embacher
 * @author Thomas Storm
 */
public class AatsrLevel1ProductFactory extends SlstrLevel1ProductFactory {

    private Product masterProduct;

    private static final double FILL_VALUE = -1.0E9;

    // todo ideas+ implement valid Expression
//    private final static String validExpression = "!quality_flags.invalid";

    public AatsrLevel1ProductFactory(AatsrLevel1ProductReader productReader) {
        super(productReader);
    }

    @Override
    protected List<String> getFileNames(Manifest manifest) {
        final List<String> fileNames = manifest.getFileNames(new String[0]);
        fileNames.sort(String::compareTo);
        return fileNames;
    }

    @Override
    protected Product readProduct(String fileName, Manifest manifest) throws IOException {
        final File file = new File(getInputFileParentDirectory(), fileName);
        if (!file.exists()) {
            return null;
        }
        final S3NetcdfReader reader;
        if (fileName.equals("met_tx.nc")) {
            reader = new MetTxReader();
        } else {
            reader = new AatsrS3NetcdfReader();
            addSeparatingDimensions(reader.getSuffixesForSeparatingDimensions());
        }
        return reader.readProductNodes(file, null);
    }

    @Override
    protected Product findMasterProduct() {
        if (masterProduct != null) {
            return masterProduct;
        }
        masterProduct = findMasterProduct(getOpenProductList());
        return masterProduct;
    }

    @Override
    protected void setUncertaintyBands(Product product) {
        final Band[] bands = product.getBands();
        for (Band band : bands) {
            final String bandName = band.getName();
            final int last_ = bandName.lastIndexOf("_");
            final String uncertaintyBandName = bandName.substring(0, last_) + "_uncert" + bandName.substring(last_);
            if (product.containsBand(uncertaintyBandName)) {
                final Band uncertaintyBand = product.getBand(uncertaintyBandName);
                band.addAncillaryVariable(uncertaintyBand, "uncertainty");
                addUncertaintyImageInfo(uncertaintyBand);
            }
        }
    }

    @Override
    protected String getProductSpecificMetadataElementName() {
        return "atsrProductInformation";
    }

    @Override
    protected void setAutoGrouping(Product[] sourceProducts, Product targetProduct) {
        String bandGrouping = getAutoGroupingString(sourceProducts);
        targetProduct.setAutoGrouping("S*_radiance*_in:S*_BT*_in:" +
                                      "S*_radiance*_io:S*_BT*_io:" +
                                      "S*exception_in:S*exception_io:" +
                                      "x_*:y_*:" +
                                      "elevation:latitude:longitude:" +
                                      "specific_humidity:temperature_profile:" +
                                      bandGrouping);
    }

    @Override
    protected void setGeoCoding(Product targetProduct) {
        TiePointGrid latGrid = null;
        TiePointGrid lonGrid = null;
        for (final TiePointGrid grid : targetProduct.getTiePointGrids()) {
            if (latGrid == null && grid.getName().endsWith("latitude_tx")) {
                latGrid = grid;
            }
            if (lonGrid == null && grid.getName().endsWith("longitude_tx")) {
                lonGrid = grid;
            }
        }

        targetProduct.setSceneGeoCoding(new TiePointGeoCoding(latGrid, lonGrid));
    }

    @Override
    protected void fixTiePointGrids(Product targetProduct) {

        String[] ANGLE_NAMES = new String[]{
                "sat_azimuth_tn",
                "sat_path_tn",
                "sat_zenith_tn",
                "solar_azimuth_tn",
                "solar_path_tn",
                "solar_zenith_tn",
                "sat_azimuth_to",
                "sat_path_to",
                "sat_zenith_to",
                "solar_azimuth_to",
                "solar_path_to",
                "solar_zenith_to",
        };

        for (TiePointGrid grid : targetProduct.getTiePointGrids()) {
            for (String angleName : ANGLE_NAMES) {
                if (grid.getName().equals(angleName)) {
                    TiePointGrid fixedGrid = getFixedAngleGrid(grid);
                    targetProduct.getTiePointGridGroup().remove(grid);
                    targetProduct.getTiePointGridGroup().add(fixedGrid);
                }
            }
        }

        TiePointGrid latGrid = targetProduct.getTiePointGrid("latitude_tx");
        TiePointGrid lonGrid = targetProduct.getTiePointGrid("longitude_tx");

        TiePointGrid fixedLatGrid = getFixedLatLonGrid(latGrid);
        targetProduct.getTiePointGridGroup().remove(latGrid);
        targetProduct.getTiePointGridGroup().add(fixedLatGrid);

        TiePointGrid fixedLonGrid = getFixedLatLonGrid(lonGrid);
        targetProduct.getTiePointGridGroup().remove(lonGrid);
        targetProduct.getTiePointGridGroup().add(fixedLonGrid);
    }

    private TiePointGrid getFixedAngleGrid(TiePointGrid grid) {
        // first, remove filled pixels at the end
        TiePointGrid endFixedGrid = getFixedLatLonGrid(grid);
        int gridWidth = grid.getGridWidth() - 5;
        int gridHeight = grid.getGridHeight();

        // second, copy values which are not fill value (everything apart from first 2 and last 3)
        float[] originalTiePoints = endFixedGrid.getTiePoints();
        float[] tiePoints = new float[gridWidth * gridHeight];

        for (int y = 0; y < grid.getGridHeight(); y++) {
            System.arraycopy(originalTiePoints, 2 + grid.getGridWidth() * y, tiePoints, gridWidth, gridWidth - 1);
        }

        return new TiePointGrid(grid.getName(), gridWidth, gridHeight, grid.getOffsetX(), grid.getOffsetY(), grid.getSubSamplingX(), grid.getSubSamplingY(), tiePoints, true);
    }

    private static TiePointGrid getFixedLatLonGrid(TiePointGrid grid) {
        int firstFillIndex = -1;
        int gridWidth = grid.getGridWidth();
        float[] originalTiePoints = grid.getTiePoints();
        for (int i = 0; i < originalTiePoints.length; i++) {
            if (originalTiePoints[i] == FILL_VALUE) {
                firstFillIndex = i;
                break;
            }
        }
        int line = firstFillIndex / grid.getGridWidth();
        int newHeight = line - 1;

        float[] tiePoints = new float[gridWidth * newHeight];
        System.arraycopy(originalTiePoints, 0, tiePoints, 0, tiePoints.length);
        return new TiePointGrid(grid.getName(), gridWidth, newHeight, grid.getOffsetX(), grid.getOffsetY(), grid.getSubSamplingX(), grid.getSubSamplingY(), tiePoints, true);

    }

    protected short[] getResolutions(String gridIndex) {
        short[] resolutions;
        if (gridIndex.startsWith("i")) {
            resolutions = new short[]{1000, 1000};
        } else if (gridIndex.startsWith("t")) {
            resolutions = new short[]{16000, 16000};
        } else {
            resolutions = new short[]{500, 500};
        }
        return resolutions;
    }

    static Product findMasterProduct(List<Product> openProductList) {
        int maxWidth = 0;
        int maxHeight = 0;
        Product masterProduct = null;
        for (Product product : openProductList) {
            final int width = product.getSceneRasterWidth();
            final int height = product.getSceneRasterHeight();
            if (width > maxWidth || height > maxHeight) {
                masterProduct = product;
                maxWidth = Math.max(maxWidth, width);
                maxHeight = Math.max(maxHeight, height);
            }
        }
        return masterProduct;
    }
}
