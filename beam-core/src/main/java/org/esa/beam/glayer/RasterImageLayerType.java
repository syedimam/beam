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

package org.esa.beam.glayer;

import com.bc.ceres.binding.Property;
import com.bc.ceres.binding.PropertySet;
import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.glayer.Layer;
import com.bc.ceres.glayer.LayerContext;
import com.bc.ceres.glayer.support.ImageLayer;
import com.bc.ceres.glevel.MultiLevelSource;
import org.esa.beam.framework.datamodel.RasterDataNode;
import org.esa.beam.glevel.BandImageMultiLevelSource;

import java.awt.geom.AffineTransform;

public class RasterImageLayerType extends ImageLayer.Type {

    public static final String PROPERTY_NAME_RASTER = "raster";

    private static final String TYPE_NAME = "RasterImageLayerType";
    private static final String[] ALIASES = {"org.esa.beam.glayer.RasterImageLayerType"};

    @Override
    public String getName() {
        return TYPE_NAME;
    }
    
    @Override
    public String[] getAliases() {
        return ALIASES;
    }
    
    @Override
    public ImageLayer createLayer(LayerContext ctx, PropertySet configuration) {
        MultiLevelSource multiLevelSource = (MultiLevelSource) configuration.getValue(
                ImageLayer.PROPERTY_NAME_MULTI_LEVEL_SOURCE);
        if (multiLevelSource == null) {
            final RasterDataNode raster = (RasterDataNode) configuration.getValue(PROPERTY_NAME_RASTER);
            final AffineTransform i2mTransform = raster.getSourceImage().getModel().getImageToModelTransform(0);
            multiLevelSource = BandImageMultiLevelSource.create(raster, i2mTransform, ProgressMonitor.NULL);
            configuration.setValue(ImageLayer.PROPERTY_NAME_MULTI_LEVEL_SOURCE, multiLevelSource);
        }
        return new ImageLayer(this, multiLevelSource, configuration);
    }

    @Override
    public PropertySet createLayerConfig(LayerContext ctx) {
        final PropertySet template = super.createLayerConfig(ctx);

        template.addProperty(Property.create(PROPERTY_NAME_RASTER, RasterDataNode.class));
        template.getDescriptor(PROPERTY_NAME_RASTER).setItemAlias("raster");
        template.getDescriptor(PROPERTY_NAME_RASTER).setNotNull(true);

        return template;
    }

    public Layer createLayer(RasterDataNode raster, MultiLevelSource multiLevelSource) {
        final PropertySet configuration = createLayerConfig(null);
        configuration.setValue(PROPERTY_NAME_RASTER, raster);
        if (multiLevelSource == null) {
            multiLevelSource = BandImageMultiLevelSource.create(raster, ProgressMonitor.NULL);
        }
        configuration.setValue(ImageLayer.PROPERTY_NAME_MULTI_LEVEL_SOURCE, multiLevelSource);
        return createLayer(null, configuration);
    }
}
