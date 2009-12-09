package org.esa.beam.visat.toolviews.layermanager.layersrc.shapefile;

import com.bc.ceres.swing.demo.FigureEditorApp;
import com.bc.ceres.swing.figure.Figure;
import com.bc.ceres.swing.figure.FigureCollection;
import com.bc.ceres.swing.figure.FigureFactory;
import com.bc.ceres.swing.figure.support.DefaultFigureStyle;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;
import org.esa.beam.framework.ui.product.SimpleFeatureFigure;
import org.esa.beam.framework.ui.product.SimpleFeatureFigureFactory;
import org.esa.beam.framework.ui.product.SimpleFeaturePointFigure;
import org.esa.beam.framework.ui.product.SimpleFeatureShapeFigure;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.DataUtilities;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.FeatureSource;
import org.geotools.data.FeatureStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FeatureFigureEditorApp extends FigureEditorApp {

    private FeatureCollection featureCollection;

    public FeatureFigureEditorApp() {
        SimpleFeatureType ft = createSimpleFeatureType("X", Geometry.class, null);
        this.featureCollection = new DefaultFeatureCollection("X", ft);
    }

    static class XYZ {
        Class<?> geometryType;
        SimpleFeatureType defaults;
        ArrayList<SimpleFeature> features = new ArrayList<SimpleFeature>();
    }

    private SimpleFeatureType createSimpleFeatureType(String typeName, Class<?> geometryType, SimpleFeatureType defaults) {
        SimpleFeatureTypeBuilder sftb = new SimpleFeatureTypeBuilder();
        if (defaults != null) {
            //sftb.init(defaults);
        }
        DefaultGeographicCRS crs = DefaultGeographicCRS.WGS84;
        sftb.setCRS(crs);
        sftb.setName(typeName);
        sftb.add("geom", geometryType);
        sftb.add("style", String.class);
        sftb.setDefaultGeometry("geom");
        return sftb.buildFeatureType();
    }

    public static void main(String[] args) {
        run(new FeatureFigureEditorApp());
    }

    @Override
    protected FigureFactory getFigureFactory() {
        return new SimpleFeatureFigureFactory(featureCollection);
    }

    @Override
    protected void loadFigureCollection(File file, FigureCollection figureCollection) throws IOException {
        FeatureSource<SimpleFeatureType, SimpleFeature> featureFeatureSource;
        FeatureCollection<SimpleFeatureType, SimpleFeature> featureTypeSimpleFeatureFeatureCollection;
        featureFeatureSource = getFeatureSource(file);
        featureTypeSimpleFeatureFeatureCollection = featureFeatureSource.getFeatures();
        Iterator<SimpleFeature> featureIterator = featureTypeSimpleFeatureFeatureCollection.iterator();
        while (featureIterator.hasNext()) {
            SimpleFeature simpleFeature = featureIterator.next();
            DefaultFigureStyle figureStyle = SimpleFeatureFigureFactory.createDefaultStyle();
            Object o = simpleFeature.getDefaultGeometry();
            if (o instanceof Point) {
                figureCollection.addFigure(new SimpleFeaturePointFigure(simpleFeature, figureStyle));
            } else {
                figureCollection.addFigure(new SimpleFeatureShapeFigure(simpleFeature, figureStyle));
            }
        }
    }

    @Override
    protected void storeFigureCollection(FigureCollection figureCollection, File file) throws IOException {

        Figure[] figures = figureCollection.getFigures();
        Map<Class<?>, List<SimpleFeature>> featureListMap = new HashMap<Class<?>, List<SimpleFeature>>();
        for (Figure figure : figures) {
            SimpleFeatureFigure simpleFeatureFigure = (SimpleFeatureFigure) figure;
            SimpleFeature simpleFeature = simpleFeatureFigure.getSimpleFeature();
            Class<?> geometryType = simpleFeature.getDefaultGeometry().getClass();
            List<SimpleFeature> featureList = featureListMap.get(geometryType);
            if (featureList == null) {
                featureList = new ArrayList<SimpleFeature>();
                featureListMap.put(geometryType, featureList);
            }
            featureList.add(simpleFeature);
        }

        Set<Map.Entry<Class<?>, List<SimpleFeature>>> entries = featureListMap.entrySet();
        for (Map.Entry<Class<?>, List<SimpleFeature>> entry : entries) {
            Class<?> geomType = entry.getKey();
            String geomName = geomType.getSimpleName();
            String basename = file.getName();
            if (basename.endsWith(".shp")) {
                basename = basename.substring(0, basename.length() - 4);
            }
            File file1 = new File(file.getParentFile(), basename + "_" + geomName + ".shp");
            System.out.println("file1 = " + file1);

            ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
            Map map = Collections.singletonMap("url", file1.toURI().toURL());
            DataStore dataStore = factory.createNewDataStore(map);
            List<SimpleFeature> features = entry.getValue();
            SimpleFeature simpleFeature = features.get(0);
            String typeName = "FT_" + geomName;
            SimpleFeatureType simpleFeatureType = createSimpleFeatureType(typeName, geomType, simpleFeature.getType());
            dataStore.createSchema(simpleFeatureType);
            FeatureStore<SimpleFeatureType, SimpleFeature> featureStore = (FeatureStore<SimpleFeatureType, SimpleFeature>) dataStore.getFeatureSource(typeName);
            DefaultTransaction transaction = new DefaultTransaction("X");
            featureStore.setTransaction(transaction);
            featureStore.addFeatures(DataUtilities.collection(features));
            try {
                transaction.commit();
            } catch (IOException e) {
                transaction.rollback();
            }
            transaction.close();

        }
    }

    public static FeatureSource<SimpleFeatureType, SimpleFeature> getFeatureSource(File file) throws IOException {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(ShapefileDataStoreFactory.URLP.key, file.toURI().toURL());
        map.put(ShapefileDataStoreFactory.CREATE_SPATIAL_INDEX.key, Boolean.TRUE);
        DataStore shapefileStore = DataStoreFinder.getDataStore(map);
        String typeName = shapefileStore.getTypeNames()[0]; // Shape files do only have one type name
        FeatureSource<SimpleFeatureType, SimpleFeature> featureSource;
        featureSource = shapefileStore.getFeatureSource(typeName);
        return featureSource;
    }

}