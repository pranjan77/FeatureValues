package us.kbase.kbasefeaturevalues.test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import junit.framework.Assert;

import org.ini4j.Ini;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import us.kbase.kbasefeaturevalues.BuildFeatureSetParams;
import us.kbase.kbasefeaturevalues.ClusterHierarchicalParams;
import us.kbase.kbasefeaturevalues.ClusterKMeansParams;
import us.kbase.kbasefeaturevalues.ClustersFromDendrogramParams;
import us.kbase.kbasefeaturevalues.CorrectMatrixParams;
import us.kbase.kbasefeaturevalues.EstimateKParams;
import us.kbase.kbasefeaturevalues.EstimateKParamsNew;
import us.kbase.kbasefeaturevalues.EstimateKResult;
import us.kbase.kbasefeaturevalues.ExpressionMatrix;
import us.kbase.kbasefeaturevalues.FeatureClusters;
import us.kbase.kbasefeaturevalues.FloatMatrix2D;
import us.kbase.kbasefeaturevalues.GetMatrixDescriptorParams;
import us.kbase.kbasefeaturevalues.GetMatrixStatParams;
import us.kbase.kbasefeaturevalues.GetSubmatrixStatParams;
import us.kbase.kbasefeaturevalues.KBaseFeatureValuesImpl;
import us.kbase.kbasefeaturevalues.KBaseFeatureValuesServer;
import us.kbase.kbasefeaturevalues.LabeledCluster;
import us.kbase.kbasefeaturevalues.MatrixDescriptor;
import us.kbase.kbasefeaturevalues.MatrixStat;
import us.kbase.kbasefeaturevalues.ReconnectMatrixToGenomeParams;
import us.kbase.kbasefeaturevalues.SubmatrixStat;
import us.kbase.kbasefeaturevalues.transform.ExpressionUploader;
import us.kbase.kbasefeaturevalues.transform.FeatureClustersDownloader;
import us.kbase.auth.AuthToken;
import us.kbase.clusterservice.ClusterResults;
import us.kbase.common.service.JsonServerSyslog;
import us.kbase.common.service.RpcContext;
import us.kbase.common.service.ServerException;
import us.kbase.common.service.Tuple2;
import us.kbase.common.service.UObject;
import us.kbase.workspace.CreateWorkspaceParams;
import us.kbase.workspace.ObjectData;
import us.kbase.workspace.ObjectIdentity;
import us.kbase.workspace.ObjectSaveData;
import us.kbase.workspace.ProvenanceAction;
import us.kbase.workspace.SaveObjectsParams;
import us.kbase.workspace.WorkspaceClient;
import us.kbase.workspace.WorkspaceIdentity;

public class KBaseFeatureValuesServerTest {
    private static AuthToken token = null;
    private static Map<String, String> config = null;
    private static WorkspaceClient wsClient = null;
    private static String wsUrl = null;
    private static String wsName = null;
    private static KBaseFeatureValuesServer impl = null;
    
    private static final String commonExpressionObjectName = "Desulfovibrio_vulgaris_Hildenborough.expression";
    
    @BeforeClass
    public static void init() throws Exception {
        token = new AuthToken(System.getenv("KB_AUTH_TOKEN"));
        String configFilePath = System.getenv("KB_DEPLOYMENT_CONFIG");
        File deploy = new File(configFilePath);
        Ini ini = new Ini(deploy);
        config = ini.get("KBaseFeatureValues");
        wsUrl = config.get("ws.url");
        wsClient = new WorkspaceClient(new URL(wsUrl), token);
        wsClient.setAuthAllowedForHttp(true);
        // These lines are necessary because we don't want to start linux syslog bridge service
        JsonServerSyslog.setStaticUseSyslog(false);
        JsonServerSyslog.setStaticMlogFile(new File(config.get("scratch"), "test.log").getAbsolutePath());
        impl = new KBaseFeatureValuesServer();
        ////////////////////////////Prepare common data //////////////////////////////
        String testWsName = getWsName();
        String contigsetObjName = "Desulfovibrio_vulgaris_Hildenborough.contigset";
        String genomeObjName = "Desulfovibrio_vulgaris_Hildenborough.genome";
        File inputDir = new File("test/data/upload1");
        File inputFile = new File(inputDir, "Desulfovibrio_vulgaris_Hildenborough_microarray_log_level_data.txt");
        Map<String, Object> contigsetData = new LinkedHashMap<String, Object>();
        contigsetData.put("contigs", new ArrayList<Object>());
        contigsetData.put("id", "1945.contigset");
        contigsetData.put("md5", "md5");
        contigsetData.put("name", "1945");
        contigsetData.put("source", "User uploaded data");
        contigsetData.put("source_id", "noid");
        contigsetData.put("type", "Organism");
        wsClient.saveObjects(new SaveObjectsParams().withWorkspace(testWsName).withObjects(Arrays.asList(
                new ObjectSaveData().withName(contigsetObjName).withType("KBaseGenomes.ContigSet")
                .withData(new UObject(contigsetData)))));
        @SuppressWarnings("unchecked")
        Map<String, Object> genomeData = UObject.getMapper().readValue(new File(inputDir,
                "Desulfovibrio_vulgaris_Hildenborough_reduced_genome.json"), Map.class);
        genomeData.put("contigset_ref", testWsName + "/" + contigsetObjName);
        wsClient.saveObjects(new SaveObjectsParams().withWorkspace(testWsName).withObjects(Arrays.asList(
                new ObjectSaveData().withName(genomeObjName).withType("KBaseGenomes.Genome")
                .withData(new UObject(genomeData)))));
        ExpressionMatrix data = ExpressionUploader.parse(wsUrl, testWsName, inputFile, "MO", 
                genomeObjName, true, null, null, token);
        wsClient.saveObjects(new SaveObjectsParams().withWorkspace(testWsName).withObjects(Arrays.asList(
                new ObjectSaveData().withName(commonExpressionObjectName)
                .withType("KBaseFeatureValues.ExpressionMatrix").withData(new UObject(data)))));
    }
    
    private static String getWsName() throws Exception {
        if (wsName == null) {
            long suffix = System.currentTimeMillis();
            wsName = "test_KBaseFeatureValues_" + suffix;
            wsClient.createWorkspace(new CreateWorkspaceParams().withWorkspace(wsName));
        }
        return wsName;
    }
    
    private static RpcContext getContext() {
        return new RpcContext().withProvenance(Arrays.asList(new ProvenanceAction()
            .withService("KBaseFeatureValues").withMethod("please_never_use_it_in_production")
            .withMethodParams(new ArrayList<UObject>())));
    }
    
    @AfterClass
    public static void cleanup() {
        if (wsName != null) {
            try {
                wsClient.deleteWorkspace(new WorkspaceIdentity().withWorkspace(wsName));
                System.out.println("Test workspace [" + wsName + "] was deleted");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
        
    private static WorkspaceClient getWsClient() {
        return wsClient;
    }

    @Test
    public void testClustersFromLabels() throws Exception {
        FloatMatrix2D matrixData = getSampleMatrix();
        ClusterResults res = new ClusterResults().withClusterLabels(
                Arrays.asList(1L, -1L, -1L, -1L, 2L, 2L, 2L))
                .withMeancor(Arrays.asList(Double.NaN, 0.9999))
                .withMsecs(Arrays.asList(Double.NaN, 0.0062));
        List<LabeledCluster> clusters = KBaseFeatureValuesImpl.clustersFromLabels(matrixData, res);
        Assert.assertEquals(2, clusters.size());
    }

    @Test
    public void testMainPipeline() throws Exception {
        WorkspaceClient wscl = getWsClient();
        String testWsName = getWsName();
        String exprObjName = "expression1";
        String estimObjName = "estimate1";
        String estimNewObjName = "estimate2";
        String clustObj1Name = "clusters1";
        String clustObj2Name = "clusters2";
        String clustObj3Name = "clusters3";
        ExpressionMatrix data = new ExpressionMatrix().withType("log-ratio").withScale("1.0")
                .withData(getSampleMatrix());
        wscl.saveObjects(new SaveObjectsParams().withWorkspace(testWsName).withObjects(Arrays.asList(
                new ObjectSaveData().withName(exprObjName).withType("KBaseFeatureValues.ExpressionMatrix")
                .withData(new UObject(data)))));
        /////////////// estimate K /////////////////
        impl.estimateK(new EstimateKParams().withInputMatrix(testWsName + "/" + 
                exprObjName).withOutWorkspace(testWsName).withOutEstimateResult(estimObjName), token, getContext());
        ObjectData res1 = wscl.getObjects(Arrays.asList(new ObjectIdentity().withWorkspace(testWsName)
                .withName(estimObjName))).get(0);
        EstimateKResult estKRes = res1.getData().asClassInstance(EstimateKResult.class);
        long k = estKRes.getBestK();
        Assert.assertEquals(3, k);
        Assert.assertEquals(5, estKRes.getEstimateClusterSizes().size());
        for (int i = 0; i < estKRes.getEstimateClusterSizes().size(); i++) {
            Tuple2 <Long, Double> item = estKRes.getEstimateClusterSizes().get(i);
            Assert.assertEquals(2L + i, (long)item.getE1());
            Assert.assertTrue((double)item.getE2() > 0);
        }
        impl.estimateKNew(new EstimateKParamsNew().withInputMatrix(testWsName + "/" + 
                exprObjName).withRandomSeed(123L).withOutWorkspace(testWsName)
                .withOutEstimateResult(estimNewObjName), token, getContext());
        ObjectData res1new = wscl.getObjects(Arrays.asList(new ObjectIdentity().withWorkspace(testWsName)
                .withName(estimNewObjName))).get(0);
        EstimateKResult estKResNew = res1new.getData().asClassInstance(EstimateKResult.class);
        long kNew = estKResNew.getBestK();
        Assert.assertEquals(k, kNew);
        //System.out.println("Cluster count qualities: " + estKResNew.getEstimateClusterSizes());
        Assert.assertEquals(estKRes.getEstimateClusterSizes().size(), estKResNew.getEstimateClusterSizes().size());
        for (int i = 0; i < estKResNew.getEstimateClusterSizes().size(); i++) {
            Tuple2<Long, Double> entry = estKRes.getEstimateClusterSizes().get(i);
            Tuple2<Long, Double> entryNew = estKResNew.getEstimateClusterSizes().get(i);
            Assert.assertEquals((long)entry.getE1(), (long)entryNew.getE1());
            Assert.assertEquals((double)entry.getE2(), (double)entryNew.getE2(), 1e-10);
        }
        /////////////// K-means /////////////////
        impl.clusterKMeans(new ClusterKMeansParams().withInputData(testWsName + "/" + 
                exprObjName).withK(k).withOutWorkspace(testWsName).withOutClustersetId(clustObj1Name),
                token, getContext());
        ObjectData res2 = wscl.getObjects(Arrays.asList(new ObjectIdentity().withWorkspace(testWsName)
                .withName(clustObj1Name))).get(0);
        FeatureClusters clSet2 = res2.getData().asClassInstance(FeatureClusters.class);
        checkKMeansForSample(clSet2);
        /////////////// Hierarchical /////////////////
        impl.clusterHierarchical(new ClusterHierarchicalParams().withInputData(testWsName + "/" + 
                exprObjName).withFeatureHeightCutoff(0.5).withOutWorkspace(testWsName)
                .withOutClustersetId(clustObj2Name), token, getContext());
        ObjectData res3 = wscl.getObjects(Arrays.asList(new ObjectIdentity().withWorkspace(testWsName)
                .withName(clustObj2Name))).get(0);
        FeatureClusters clSet3 = res3.getData().asClassInstance(FeatureClusters.class);
        // TODO: check why it's not different from clSet4 case
        Assert.assertEquals(2, clSet3.getFeatureClusters().size());
        Assert.assertEquals(5, clSet3.getFeatureClusters().get(0).getIdToPos().size());
        Assert.assertEquals(2, clSet3.getFeatureClusters().get(1).getIdToPos().size());
        Assert.assertTrue(clSet3.getFeatureDendrogram().startsWith("("));
        Assert.assertTrue(clSet3.getFeatureDendrogram().endsWith(");"));
        /////////////// From dendrogram /////////////////
        impl.clustersFromDendrogram(new ClustersFromDendrogramParams().withInputData(testWsName + "/" + 
                clustObj2Name).withFeatureHeightCutoff(0.2).withOutWorkspace(testWsName)
                .withOutClustersetId(clustObj3Name), token, getContext());
        ObjectData res4 = wscl.getObjects(Arrays.asList(new ObjectIdentity().withWorkspace(testWsName)
                .withName(clustObj3Name))).get(0);
        FeatureClusters clSet4 = res4.getData().asClassInstance(FeatureClusters.class);
        Assert.assertEquals(3, clSet4.getFeatureClusters().size());
        Assert.assertEquals(2, clSet4.getFeatureClusters().get(0).getIdToPos().size());
        Assert.assertEquals(2, clSet4.getFeatureClusters().get(1).getIdToPos().size());
        Assert.assertEquals(3, clSet4.getFeatureClusters().get(2).getIdToPos().size());
        /////////////// Clusters download ///////////////
        File tempDir = new File("test/temp");
        File tsvTempFile = new File(tempDir, "clusters.tsv");
        try {
            FeatureClustersDownloader.generate(wsUrl, testWsName, clustObj1Name, 1, "TSV", token,
                    new PrintWriter(tsvTempFile));
            List<String> lines = readFileLines(tsvTempFile);
            Assert.assertEquals(7, lines.size());
            Set<String> clusterCodes = new TreeSet<String>();
            for (String l : lines) {
                String[] parts = l.split(Pattern.quote("\t"));
                Assert.assertEquals(2, parts.length);
                clusterCodes.add(parts[1]);
            }
            Assert.assertEquals("[0, 1, 2]", clusterCodes.toString());
        } finally {
            if (tsvTempFile.exists())
                try {
                    tsvTempFile.delete();
                } catch (Exception ignore) {}
        }
    }

    private static void checkKMeansForSample(FeatureClusters clSet) {
        Assert.assertEquals(3, clSet.getFeatureClusters().size());
        Assert.assertEquals(2, clSet.getFeatureClusters().get(0).getIdToPos().size());
        Assert.assertEquals(0.9999, (double)clSet.getFeatureClusters().get(0).getMeancor(), 1e-4);
        Assert.assertEquals(0.018, (double)clSet.getFeatureClusters().get(0).getMsec(), 1e-4);
        Assert.assertEquals(2, clSet.getFeatureClusters().get(1).getIdToPos().size());
        Assert.assertEquals(0.9982, (double)clSet.getFeatureClusters().get(1).getMeancor(), 1e-4);
        Assert.assertEquals(0.0184, (double)clSet.getFeatureClusters().get(1).getMsec(), 1e-4);
        Assert.assertEquals(3, clSet.getFeatureClusters().get(2).getIdToPos().size());
        Assert.assertEquals(0.9999, (double)clSet.getFeatureClusters().get(2).getMeancor(), 1e-4);
        Assert.assertEquals(0.0062, (double)clSet.getFeatureClusters().get(2).getMsec(), 1e-4);
    }
    
    @Test
    public void testPyScikitKMeans() throws Exception {
        String testWsName = getWsName();
        String osName = System.getProperty("os.name");
        if (osName.toLowerCase().contains("mac"))
            return;
        String exprObjName = "py_expression1";
        String clustObj1Name = "py_clusters1";
        ExpressionMatrix data = new ExpressionMatrix().withType("log-ratio").withScale("1.0")
                .withData(getSampleMatrix());
        WorkspaceClient wscl = getWsClient();
        wscl.saveObjects(new SaveObjectsParams().withWorkspace(testWsName).withObjects(Arrays.asList(
                new ObjectSaveData().withName(exprObjName).withType("KBaseFeatureValues.ExpressionMatrix")
                .withData(new UObject(data)))));
        impl.clusterKMeans(new ClusterKMeansParams().withInputData(testWsName + "/" + 
                exprObjName).withK(3L).withOutWorkspace(testWsName).withOutClustersetId(clustObj1Name),
                token, getContext());
        ObjectData res = wscl.getObjects(Arrays.asList(new ObjectIdentity().withWorkspace(testWsName)
                .withName(clustObj1Name))).get(0);
        FeatureClusters clSet = res.getData().asClassInstance(FeatureClusters.class);
        checkKMeansForSample(clSet);
    }
    
    @Test
    public void testCorrectMatrix() throws Exception {
        String testWsName = getWsName();
        String sourceMatrixId = "notcorrected_matrix.1";
        ExpressionMatrix data = new ExpressionMatrix().withType("log-ratio").withScale("1.0")
                .withData(getSampleMatrix());
        data.getData().getValues().get(0).set(0, null);
        Assert.assertEquals(1, getNullCount(data.getData()));
        getWsClient().saveObjects(new SaveObjectsParams().withWorkspace(testWsName).withObjects(Arrays.asList(
                new ObjectSaveData().withName(sourceMatrixId).withType("KBaseFeatureValues.ExpressionMatrix")
                .withData(new UObject(data)))));
        String targetMatrixId = "corrected_matrix.1";
        impl.correctMatrix(new CorrectMatrixParams().withInputData(
                testWsName + "/" + sourceMatrixId).withOutWorkspace(testWsName)
                .withOutMatrixId(targetMatrixId).withTransformType("missing"), token, getContext());
        ExpressionMatrix matrix = getWsClient().getObjects(Arrays.asList(
                new ObjectIdentity().withWorkspace(testWsName).withName(targetMatrixId)))
                .get(0).getData().asClassInstance(ExpressionMatrix.class);
        Assert.assertEquals(0, getNullCount(matrix.getData()));
        Assert.assertEquals(0.325, (double)matrix.getData().getValues().get(0).get(0), 1e-10);
    }
    
    @Test
    public void testReconnectToGenome() throws Exception {
        String testWsName = getWsName();
        String genomeObjName = "Desulfovibrio_vulgaris_Hildenborough.genome";
        File inputDir = new File("test/data/upload1");
        File inputFile = new File(inputDir, "Desulfovibrio_vulgaris_Hildenborough_microarray_log_level_data.txt");
        ExpressionMatrix data = ExpressionUploader.parse(null, null, inputFile, "MO", 
                null, true, null, null, null);
        String matrixId = "connected_matrix.1";
        getWsClient().saveObjects(new SaveObjectsParams().withWorkspace(testWsName).withObjects(Arrays.asList(
                new ObjectSaveData().withName(matrixId).withType("KBaseFeatureValues.ExpressionMatrix")
                .withData(new UObject(data)))));
        impl.reconnectMatrixToGenome(new ReconnectMatrixToGenomeParams().withInputData(
                testWsName + "/" + matrixId).withOutWorkspace(testWsName).withGenomeRef(
                        testWsName + "/" + genomeObjName), token, getContext());
        ExpressionMatrix matrix = getWsClient().getObjects(Arrays.asList(
                new ObjectIdentity().withWorkspace(testWsName).withName(matrixId)))
                .get(0).getData().asClassInstance(ExpressionMatrix.class);
        Assert.assertEquals(2666, matrix.getFeatureMapping().size());
    }
    
    @Test
    public void testBuildRowDescriptors() throws Exception {
        String testWsName = getWsName();
        ExpressionMatrix data = UObject.getMapper().readValue(new File("test/data/upload7/poplar_roots_exp_matrix_5000.json"), ExpressionMatrix.class);
        String matrixId = "row_descr_matrix.1";
        getWsClient().saveObjects(new SaveObjectsParams().withWorkspace(testWsName).withObjects(Arrays.asList(
                new ObjectSaveData().withName(matrixId).withType("KBaseFeatureValues.ExpressionMatrix")
                .withData(new UObject(data)))));
        try {
            MatrixStat stat = impl.getMatrixStat(new GetMatrixStatParams().withInputData(testWsName + "/" + matrixId),
                    token, getContext());
            Assert.assertEquals(4999, stat.getRowStats().size());
        } catch (ServerException ex) {
            System.err.println(ex.getData());
            throw ex;
        }
    }

    private static int getNullCount(FloatMatrix2D matrix) {
        int ret = 0;
        for (List<Double> row : matrix.getValues())
            for (Double value : row)
                if (value == null)
                    ret++;
        return ret;
    }
    
    @Test
    public void testDataAPI() throws Exception {
        String testWsName = getWsName();
        MatrixDescriptor md1 = impl.getMatrixDescriptor(new GetMatrixDescriptorParams().withInputData(
                testWsName + "/" + commonExpressionObjectName), token, getContext());
        Assert.assertEquals(2680L, (long)md1.getRowsCount());
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void testBuildFeatureSet() throws Exception {
        String testWsName = getWsName();
        String genomeObjName = "Desulfovibrio_vulgaris_Hildenborough.genome";
        String outFeatureSetObj1 = "featureset.1";
        Map<String, List<String>> elements1 = buildFeatureSetForTesting(genomeObjName, 
                outFeatureSetObj1, " DVUA0001 \nDVUA0075, DVUA0112", null);
        Assert.assertEquals(3, elements1.size());
        Assert.assertEquals(3, buildFeatureSetForTesting(genomeObjName, 
                "featureset.1b", " DVUA0001 \nDVUA0075", "DVUA0112").size());
        Assert.assertEquals(3, buildFeatureSetForTesting(genomeObjName, 
                "featureset.1c", " DVUA0001 \n", "DVUA0075, DVUA0112").size());
        Assert.assertEquals(3, buildFeatureSetForTesting(genomeObjName, 
                "featureset.1d", "", " DVUA0001 \nDVUA0075, DVUA0112").size());
        Assert.assertEquals(3, buildFeatureSetForTesting(genomeObjName, 
                "featureset.1e", null, " DVUA0001 \nDVUA0075, DVUA0112").size());
        String outFeatureSetObj2 = "featureset.2";
        try {
            impl.buildFeatureSet(new BuildFeatureSetParams().withDescription("Testing...")
                    .withBaseFeatureSet(testWsName + "/" + outFeatureSetObj1)
                    .withFeatureIds("DVUA1000").withGenome(testWsName + "/" + genomeObjName)
                    .withOutWorkspace(testWsName).withOutputFeatureSet(outFeatureSetObj2),
                    token, getContext());
            Assert.fail("Method should fail");
        } catch (Exception ex) {
            Assert.assertTrue(ex.getMessage(), ex.getMessage().contains("Some features are not found: "));
        }
        impl.buildFeatureSet(new BuildFeatureSetParams().withDescription("Testing...")
                .withBaseFeatureSet(testWsName + "/" + outFeatureSetObj1)
                .withFeatureIds("DVU1000").withGenome(testWsName + "/" + genomeObjName)
                .withOutWorkspace(testWsName).withOutputFeatureSet(outFeatureSetObj2),
                token, getContext());
        Map<String, Object> fs2 = getWsClient().getObjects(Arrays.asList(
                new ObjectIdentity().withWorkspace(testWsName).withName(outFeatureSetObj2)))
                .get(0).getData().asClassInstance(Map.class);
        Map<String, List<String>> elements2 = (Map<String, List<String>>)fs2.get("elements");
        Assert.assertEquals(4, elements2.size());
    }

    @SuppressWarnings("unchecked")
    public Map<String, List<String>> buildFeatureSetForTesting(
            String genomeObjName, String outFeatureSetObj1, String featureIds,
            String featureIdsCustom) throws Exception {
        String testWsName = getWsName();
        impl.buildFeatureSet(new BuildFeatureSetParams().withDescription("Testing...")
                .withFeatureIds(featureIds).withFeatureIdsCustom(featureIdsCustom)
                .withGenome(testWsName + "/" + genomeObjName)
                .withOutWorkspace(testWsName).withOutputFeatureSet(outFeatureSetObj1),
                token, getContext());
        Map<String, Object> fs1 = getWsClient().getObjects(Arrays.asList(
                new ObjectIdentity().withWorkspace(testWsName).withName(outFeatureSetObj1)))
                .get(0).getData().asClassInstance(Map.class);
        Map<String, List<String>> elements1 = (Map<String, List<String>>)fs1.get("elements");
        return elements1;
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void testSubMatrixStat() throws Exception {
        String testWsName = getWsName();
        File dir = new File("test/data/upload8");
        GZIPInputStream is = new GZIPInputStream(new FileInputStream(new File(dir, "Rhodobacter.contigset.json.gz")));
        Map<String, Object> contigsetData = UObject.getMapper().readValue(is, Map.class);
        is.close();
        String contigsetObjName = "submatrix_contigset.1";
        getWsClient().saveObjects(new SaveObjectsParams().withWorkspace(testWsName).withObjects(Arrays.asList(
                new ObjectSaveData().withName(contigsetObjName).withType("KBaseGenomes.ContigSet")
                .withData(new UObject(contigsetData)))));
        is = new GZIPInputStream(new FileInputStream(new File(dir, "Rhodobacter.genome.json.gz")));
        Map<String, Object> genomeData = UObject.getMapper().readValue(is, Map.class);
        is.close();
        String genomeObjName = "submatrix_contigset.1";
        genomeData.put("contigset_ref", testWsName + "/" + contigsetObjName);
        getWsClient().saveObjects(new SaveObjectsParams().withWorkspace(testWsName).withObjects(Arrays.asList(
                new ObjectSaveData().withName(genomeObjName).withType("KBaseGenomes.Genome")
                .withData(new UObject(genomeData)))));
        ExpressionMatrix data = UObject.getMapper().readValue(new File(dir, "NewFakeData2.3.json"), ExpressionMatrix.class);
        data.setGenomeRef(testWsName + "/" + genomeObjName);
        String matrixId = "submatrix_matrix.1";
        getWsClient().saveObjects(new SaveObjectsParams().withWorkspace(testWsName).withObjects(Arrays.asList(
                new ObjectSaveData().withName(matrixId).withType("KBaseFeatureValues.ExpressionMatrix")
                .withData(new UObject(data)))));
        try {
            SubmatrixStat stat = impl.getSubmatrixStat(new GetSubmatrixStatParams().withInputData(testWsName + "/" + matrixId)
                    .withRowIds(Arrays.asList("RSP_0049", "RSP_1584", "RSP_1588")).withFlRowPairwiseCorrelation(1L)
                    .withFlRowSetStats(1L), token, getContext());
            Assert.assertEquals(3, stat.getRowPairwiseCorrelation().getComparisonValues().size());
            Assert.assertEquals(3, stat.getRowPairwiseCorrelation().getComparisonValues().get(0).size());
        } catch (ServerException ex) {
            System.err.println(ex.getData());
            throw ex;
        }
    }
    
    @Test
    public void testHierarchicalClustering() throws Exception {
        String testWsName = getWsName();
        String matrixId = "hierarchical_matrix.1";
        String clustObjName = "hierarchical_clusters.1";
        File inputFile = new File("test/data/upload6/E_coli_v4_Build_6_subdata.tsv");
        ExpressionMatrix data = ExpressionUploader.parse(null, null, inputFile, "Simple", 
                null, true, null, null, null);
        getWsClient().saveObjects(new SaveObjectsParams().withWorkspace(testWsName).withObjects(Arrays.asList(
                new ObjectSaveData().withName(matrixId).withType("KBaseFeatureValues.ExpressionMatrix")
                .withData(new UObject(data)))));
        impl.clusterHierarchical(new ClusterHierarchicalParams().withInputData(testWsName + "/" + 
                matrixId).withFeatureHeightCutoff(0.2).withAlgorithm("flashClust").withMaxItems(1000L)
                .withOutWorkspace(testWsName).withOutClustersetId(clustObjName), token, getContext());
        ObjectData res1 = getWsClient().getObjects(Arrays.asList(new ObjectIdentity().withWorkspace(testWsName)
                .withName(clustObjName))).get(0);
        FeatureClusters clSet1 = res1.getData().asClassInstance(FeatureClusters.class);
        Assert.assertEquals(108, clSet1.getFeatureClusters().size());
        int nullCount = 0;
        for (LabeledCluster lc : clSet1.getFeatureClusters()) {
            if (lc.getMeancor() == null || lc.getMsec() == null)
                nullCount++;
        }
        Assert.assertEquals(10, nullCount);
    }
    
    private static FloatMatrix2D getSampleMatrix() {
        List<List<Double>> values = new ArrayList<List<Double>>();
        values.add(Arrays.asList(13.0, 2.0, 3.0));
        values.add(Arrays.asList(10.9, 1.95, 2.9));
        values.add(Arrays.asList(2.45, 13.4, 4.4));
        values.add(Arrays.asList(2.5, 11.5, 3.55));
        values.add(Arrays.asList(-1.05, -2.0, -14.0));
        values.add(Arrays.asList(-1.2, -2.25, -13.2));
        values.add(Arrays.asList(-1.1, -2.1, -15.15));
        return new FloatMatrix2D().withValues(values)
                .withRowIds(Arrays.asList("g1", "g2", "g3", "g4", "g5", "g6", "g7"))
                .withColIds(Arrays.asList("c1", "c2", "c3"));
    }
    
    private static List<String> readFileLines(File f) throws IOException {
        List<String> ret = new ArrayList<String>();
        BufferedReader br = new BufferedReader(new FileReader(f));
        while (true) {
            String l = br.readLine();
            if (l == null)
                break;
            ret.add(l);
        }
        br.close();
        return ret;
    }
}
