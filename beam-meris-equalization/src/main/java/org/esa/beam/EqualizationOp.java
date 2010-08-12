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
import com.bc.ceres.core.SubProgressMonitor;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.MetadataElement;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.RasterDataNode;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.util.Guardian;
import org.esa.beam.util.ProductUtils;
import org.esa.beam.util.math.RsMathUtils;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;

import static org.esa.beam.dataio.envisat.EnvisatConstants.*;


// todo (mp) - include radiometric recalibration (CB to provide algo)

@OperatorMetadata(alias = "Equalize",
                  description = "Performs removal of detector-to-detector systematic " +
                                "radiometric differences in MERIS L1b data products.",
                  authors = "Marc Bouvet (ESTEC), Marco Peters (Brockmann Consult)",
                  copyright = "(c) 2010 by Brockmann Consult",
                  version = "1.0")
public class EqualizationOp extends Operator {

    @Parameter(defaultValue = "true",
               label = "Perform Smile correction",
               description = "Whether to perform Smile correction or not.")
    private boolean doSmile;

    @SourceProduct(alias = "source", label = "Name", description = "The source product.",
                   bands = {
                           MERIS_L1B_FLAGS_DS_NAME, MERIS_DETECTOR_INDEX_DS_NAME,
                           MERIS_L1B_RADIANCE_1_BAND_NAME,
                           MERIS_L1B_RADIANCE_2_BAND_NAME,
                           MERIS_L1B_RADIANCE_3_BAND_NAME,
                           MERIS_L1B_RADIANCE_4_BAND_NAME,
                           MERIS_L1B_RADIANCE_5_BAND_NAME,
                           MERIS_L1B_RADIANCE_6_BAND_NAME,
                           MERIS_L1B_RADIANCE_7_BAND_NAME,
                           MERIS_L1B_RADIANCE_8_BAND_NAME,
                           MERIS_L1B_RADIANCE_9_BAND_NAME,
                           MERIS_L1B_RADIANCE_10_BAND_NAME,
                           MERIS_L1B_RADIANCE_11_BAND_NAME,
                           MERIS_L1B_RADIANCE_12_BAND_NAME,
                           MERIS_L1B_RADIANCE_13_BAND_NAME,
                           MERIS_L1B_RADIANCE_14_BAND_NAME,
                           MERIS_L1B_RADIANCE_15_BAND_NAME
                   })
    private Product sourceProduct;

    @TargetProduct(description = "The target product.")
    private Product targetProduct;

    private static final String ELEM_NAME_MPH = "MPH";
    private static final String ATTRIB_SOFTWARE_VER = "SOFTWARE_VER";
    private static final String UNIT_DL = "dl";
    private static final String TARGET_BAND_PREFIX = "reflec";
    private static final String INVALID_MASK_NAME = "invalid";
    private static final String LAND_MASK_NAME = "land";

    private EqualizationLUT equalizationLUT;
    private SmileAlgorithm smileAlgorithm;
    private HashMap<String, String> bandNameMap;
    private long date;


    @Override
    public void initialize() throws OperatorException {
        Guardian.assertTrue(String.format("Source product must contain band '%s'.", MERIS_DETECTOR_INDEX_DS_NAME),
                            sourceProduct.containsBand(MERIS_DETECTOR_INDEX_DS_NAME));
        Guardian.assertTrue(String.format("Source product must contain band '%s'.", MERIS_L1B_FLAGS_DS_NAME),
                            sourceProduct.containsBand(MERIS_L1B_FLAGS_DS_NAME));
        Guardian.assertTrue(String.format("Source product must contain tie-point grid '%s'.", MERIS_SUN_ZENITH_DS_NAME),
                            sourceProduct.containsTiePointGrid(MERIS_SUN_ZENITH_DS_NAME));
        Guardian.assertTrue("Source product must be of type MERIS L1b.",
                            MERIS_L1_TYPE_PATTERN.matcher(sourceProduct.getProductType()).matches());
        Guardian.assertTrue("Source product does not contain radiance bands.", containsRadianceBands(sourceProduct));
        final ProductData.UTC startTime = sourceProduct.getStartTime();
        Guardian.assertNotNull("Source product must have a start time", startTime);

        try {
            final boolean isFullResolution = sourceProduct.getProductType().startsWith("MER_F");
            equalizationLUT = new EqualizationLUT(getReprocessingVersion(), isFullResolution);
        } catch (IOException e) {
            throw new OperatorException("Not able to create LUT.", e);
        }
        // compute julian date
        final Calendar calendar = startTime.getAsCalendar();
        long productJulianDate = (long) JulianDate.julianDate(calendar.get(Calendar.YEAR),
                                                              calendar.get(Calendar.MONTH),
                                                              calendar.get(Calendar.DAY_OF_MONTH));
        date = productJulianDate - (long) JulianDate.julianDate(2002, 4, 1);

        try {
            smileAlgorithm = new SmileAlgorithm(sourceProduct.getProductType());
        } catch (IOException e) {
            throw new OperatorException("Not able to initialise SMILE algorithm.", e);
        }

        // create the target product
        final int rasterWidth = sourceProduct.getSceneRasterWidth();
        final int rasterHeight = sourceProduct.getSceneRasterHeight();
        targetProduct = new Product(String.format("%s_Equalized", sourceProduct.getName()),
                                    String.format("%s_EQ", sourceProduct.getProductType()),
                                    rasterWidth, rasterHeight);
        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
        targetProduct.setDescription("MERIS Equalized TOA Reflectance");
        targetProduct.setAutoGrouping(TARGET_BAND_PREFIX);

        bandNameMap = new HashMap<String, String>();
        String[] sourceSpectralBandNames = getSpectralBandNames(sourceProduct);
        for (String spectralBandName : sourceSpectralBandNames) {
            final Band sourceBand = sourceProduct.getBand(spectralBandName);
            final int bandIndex = sourceBand.getSpectralBandIndex() + 1;
            final String targetBandName = TARGET_BAND_PREFIX + "_" + bandIndex;
            final Band targetBand = targetProduct.addBand(targetBandName,
                                                          ProductData.TYPE_FLOAT32);
            bandNameMap.put(targetBandName, spectralBandName);
            targetBand.setDescription("Equalized TOA reflectance band " + bandIndex);
            targetBand.setUnit(UNIT_DL);
            targetBand.setValidPixelExpression(sourceBand.getValidPixelExpression());
            ProductUtils.copySpectralBandProperties(sourceBand, targetBand);
        }

        copyBand(MERIS_DETECTOR_INDEX_DS_NAME);
        ProductUtils.copyFlagBands(sourceProduct, targetProduct);
        final Band sourceFlagBand = sourceProduct.getBand(MERIS_L1B_FLAGS_DS_NAME);
        final Band targetFlagBand = targetProduct.getBand(MERIS_L1B_FLAGS_DS_NAME);
        targetFlagBand.setSourceImage(sourceFlagBand.getSourceImage());
        ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        targetProduct.setStartTime(sourceProduct.getStartTime());
        targetProduct.setEndTime(sourceProduct.getEndTime());
    }

    private boolean containsRadianceBands(Product product) {
        for (String name : MERIS_L1B_SPECTRAL_BAND_NAMES) {
            product.containsBand(name);
            if (!product.containsBand(name)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        pm.beginTask("Performing equalization...", 7);
        final Rectangle targetRegion = targetTile.getRectangle();
        final int spectralIndex = targetBand.getSpectralBandIndex();
        try {
            final String sourceBandName = bandNameMap.get(targetBand.getName());
            final Band sourceBand = sourceProduct.getBand(sourceBandName);
            final Tile sourceBandTile = loadSourceTile(sourceBandName, targetRegion, pm);
            final Tile detectorSourceTile = loadSourceTile(MERIS_DETECTOR_INDEX_DS_NAME, targetRegion, pm);
            final Tile sunZenithTile = loadSourceTile(MERIS_SUN_ZENITH_DS_NAME, targetRegion, pm);

            Tile[] radianceTiles = new Tile[0];
            Tile landMaskTile = null;
            Tile invalidMaskTile = null;
            if (doSmile) {
                radianceTiles = loadRequiredRadianceTiles(spectralIndex, targetRegion, new SubProgressMonitor(pm, 1));
                invalidMaskTile = loadSourceTile(INVALID_MASK_NAME, targetRegion, pm);
                landMaskTile = loadSourceTile(LAND_MASK_NAME, targetRegion, pm);
            }

            for (int y = targetTile.getMinY(); y <= targetTile.getMaxY(); y++) {
                checkForCancellation(pm);
                for (int x = targetTile.getMinX(); x <= targetTile.getMaxX(); x++) {

                    final int detectorIndex = detectorSourceTile.getSampleInt(x, y);
                    if (detectorIndex != -1) {
                        double sourceSample = sourceBandTile.getSampleDouble(x, y);
                        if (doSmile && !invalidMaskTile.getSampleBoolean(x, y)) {
                            sourceSample = smileAlgorithm.correct(x, y, spectralIndex,
                                                                  detectorIndex, radianceTiles,
                                                                  landMaskTile.getSampleBoolean(x, y));
                        }
                        final float solarFlux = sourceBand.getSolarFlux();
                        final double sunZenithSample = sunZenithTile.getSampleDouble(x, y);
                        final double sourceReflectance = RsMathUtils.radianceToReflectance((float) sourceSample,
                                                                                           (float) sunZenithSample,
                                                                                           solarFlux);

                        double equalizedResult = performEqualization(spectralIndex, sourceReflectance, detectorIndex);
                        targetTile.setSample(x, y, equalizedResult);
                    }
                }
            }
            pm.worked(1);
        } finally {
            pm.done();
        }
    }

    private double performEqualization(int bandIndex, double reflectanceValue, int detectorIndex) {
        final double[] coefficients = equalizationLUT.getCoefficients(bandIndex, detectorIndex);
        double cEq = coefficients[0] +
                     coefficients[1] * date +
                     coefficients[2] * date * date;
        return reflectanceValue / cEq;
    }

    static int parseReprocessingVersion(String processorName, float processorVersion) {
        if ("MERIS".equalsIgnoreCase(processorName)) {
            if (processorVersion == 4.1f || (processorVersion >= 5.02f && processorVersion <= 5.05f)) {
                return 2;
            }
        }
        if ("MEGS-PC".equalsIgnoreCase(processorName)) {
            if (processorVersion == 8.0f) {
                return 3;
            } else { //noinspection ConstantConditions
                if (processorVersion == 7.4f || processorVersion == 7.41f) {
                    return 2;
                }
            }
        }

        throw new OperatorException(
                String.format("Unknown reprocessing version %s.\nProduct must be of reprocessing 2 or 3.",
                              processorVersion));
    }

    private int getReprocessingVersion() {
        final MetadataElement mphElement = sourceProduct.getMetadataRoot().getElement(ELEM_NAME_MPH);
        if (mphElement != null) {
            final String softwareVer = mphElement.getAttributeString(ATTRIB_SOFTWARE_VER);
            if (softwareVer != null) {
                final String[] strings = softwareVer.split("/");
                final String processorName = strings[0];
                final String processorVersion = strings[1];
                final float version = Float.parseFloat(processorVersion);
                return parseReprocessingVersion(processorName, version);
            } else {
                throw new OperatorException(
                        "Not able to detect reprocessing version.\nMetadata attribute 'MPH/SOFTWARE_VER' not found.");
            }
        }
        throw new OperatorException("Not able to detect reprocessing version.\nMetadata element 'MPH' not found.");
    }

    private Tile[] loadRequiredRadianceTiles(int spectralBandIndex, Rectangle targetRectangle, ProgressMonitor pm) {
        final int[] requiredBandIndices = smileAlgorithm.computeRequiredBandIndexes(spectralBandIndex);
        Tile[] radianceTiles = new Tile[MERIS_L1B_NUM_SPECTRAL_BANDS];
        pm.beginTask("Loading radiance tiles...", requiredBandIndices.length);
        try {
            for (int requiredBandIndex : requiredBandIndices) {
                final Band band = sourceProduct.getBandAt(requiredBandIndex);
                radianceTiles[requiredBandIndex] = getSourceTile(band, targetRectangle, new SubProgressMonitor(pm, 1));
            }
        } finally {
            pm.done();
        }
        return radianceTiles;
    }

    private Tile loadSourceTile(String sourceNodeName, Rectangle rectangle, ProgressMonitor pm) {
        final RasterDataNode sourceNode = sourceProduct.getRasterDataNode(sourceNodeName);
        return getSourceTile(sourceNode, rectangle, new SubProgressMonitor(pm, 1));
    }

    private void copyBand(String sourceBandName) {
        final Band destBand = ProductUtils.copyBand(sourceBandName, sourceProduct, targetProduct);
        Band srcBand = sourceProduct.getBand(sourceBandName);
        destBand.setSourceImage(srcBand.getSourceImage());
    }

    private String[] getSpectralBandNames(Product sourceProduct) {
        final Band[] bands = sourceProduct.getBands();
        final List<String> spectralBandNames = new ArrayList<String>(bands.length);
        for (Band band : bands) {
            if (band.getSpectralBandIndex() != -1) {
                spectralBandNames.add(band.getName());
            }
        }
        return spectralBandNames.toArray(new String[spectralBandNames.size()]);
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(EqualizationOp.class);
        }

    }
}