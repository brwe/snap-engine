/*
 * Copyright (C) 2010 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.beam;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.dataio.envisat.EnvisatConstants;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.gpf.operators.meris.MerisBasisOp;
import org.esa.beam.gpf.operators.standard.BandMathsOp;
import org.esa.beam.processor.smile.SmileConstants;
import org.esa.beam.util.ProductUtils;

import java.awt.Rectangle;
import java.io.IOException;

@OperatorMetadata(alias = "Smile",
                  version = "1.0",
                  authors = "Marco Zühlke",
                  copyright = "(c) 2010 by Brockmann Consult",
                  description = "Smile correction.",
                  internal = false)
public class SmileOp extends MerisBasisOp {

    private static final double SPECTRAL_BAND_SF_FACTOR = 1.1;

    @SourceProduct(alias = "source", label = "Name", description = "The source product.")
    private Product sourceProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    private SmileAlgorithm smileAlgorithm;
    private Band landMaskBand;
    private Band validMaskBand;

    @Override
    public void initialize() throws OperatorException {

        // create the target product
        final int rasterWidth = sourceProduct.getSceneRasterWidth();
        final int rasterHeight = sourceProduct.getSceneRasterHeight();
        targetProduct = new Product(String.format("%s_Smile", sourceProduct.getName()),
                                    sourceProduct.getProductType(),
                                    rasterWidth, rasterHeight);
        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);

        for (Band sourceBand : sourceProduct.getBands()) {
            if (sourceBand.getSpectralBandIndex() != -1) {
                Band targetBand = ProductUtils.copyBand(sourceBand.getName(), sourceProduct, targetProduct);
                final double sfOld = sourceBand.getScalingFactor();
                final double sfNew = sfOld * SPECTRAL_BAND_SF_FACTOR;
                targetBand.setScalingFactor(sfNew);
            }
        }
        final Band destBand = ProductUtils.copyBand("detector_index", sourceProduct, targetProduct);
        destBand.setSourceImage(sourceProduct.getBand("detector_index").getSourceImage());

        ProductUtils.copyFlagBands(sourceProduct, targetProduct);
        targetProduct.getBand("l1_flags").setSourceImage(sourceProduct.getBand("l1_flags").getSourceImage());

        // for MERIS FSG / FRG products   TODO check: do this ???
//        copyBand(EnvisatConstants.MERIS_AMORGOS_L1B_CORR_LATITUDE_BAND_NAME, sourceProduct, targetProduct);
//        copyBand(EnvisatConstants.MERIS_AMORGOS_L1B_CORR_LONGITUDE_BAND_NAME, sourceProduct, targetProduct);
//        copyBand(EnvisatConstants.MERIS_AMORGOS_L1B_ALTIUDE_BAND_NAME, sourceProduct, targetProduct);

        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);

        targetProduct.setStartTime(sourceProduct.getStartTime());
        targetProduct.setEndTime(sourceProduct.getEndTime());

        landMaskBand = createMask(SmileConstants.BITMASK_TERM_LAND);
        validMaskBand = createMask(SmileConstants.BITMASK_TERM_PROCESS);

        try {
            smileAlgorithm = new SmileAlgorithm(sourceProduct);
        } catch (IOException e) {
            throw new OperatorException("Could not load Smile auxdata.");
        }
    }

    private Band createMask(String expression) {
        BandMathsOp mathsOp = BandMathsOp.createBooleanExpressionBand(expression, sourceProduct);
        return mathsOp.getTargetProduct().getBandAt(0);
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        Rectangle rect = targetTile.getRectangle();
        int bandIndex = sourceProduct.getBandIndex(targetBand.getName());
        int[] requiredBands = smileAlgorithm.computeRequiredBandIndexes(bandIndex);

        pm.beginTask("smile correction", requiredBands.length + 3 + targetTile.getHeight());
        try {
            Tile[] radinaceTiles = new Tile[EnvisatConstants.MERIS_L1B_NUM_SPECTRAL_BANDS];
            for (int index : requiredBands) {
                radinaceTiles[index] = getSourceTile(sourceProduct.getBandAt(index), rect, ProgressMonitor.NULL);
            }

            Tile detectorIndexTile = getSourceTile(sourceProduct.getRasterDataNode("detector_index"),
                    rect, ProgressMonitor.NULL);
            Tile isLandTile = getSourceTile(landMaskBand, rect, ProgressMonitor.NULL);
            Tile isValidTile = getSourceTile(validMaskBand, rect, ProgressMonitor.NULL);

            int maxDetectorIndex = smileAlgorithm.getMaxDetectorIndex();

            for (int y = targetTile.getMinY(); y <= targetTile.getMaxY(); y++) {
                for (int x = targetTile.getMinX(); x <= targetTile.getMaxX(); x++) {
                    checkForCancellation(pm);
                    int detectorIndex = detectorIndexTile.getSampleInt(x, y);
                    boolean correctionPossible = detectorIndex >= 0 &&
                            detectorIndex < maxDetectorIndex &&
                            isValidTile.getSampleBoolean(x, y);

                    double correctedValue;
                    if (correctionPossible) {
                        correctedValue = smileAlgorithm.correct(x, y, bandIndex, detectorIndex, radinaceTiles, isLandTile.getSampleBoolean(x, y));
                    } else {
                        correctedValue = radinaceTiles[bandIndex].getSampleDouble(x, y);
                    }
                    targetTile.setSample(x, y, correctedValue);
                }
            }
        }finally {
            pm.done();
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(SmileOp.class);
        }
    }    
}
