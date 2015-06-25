package us.kbase.clusterservice;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import us.kbase.auth.AuthToken;
import us.kbase.common.service.JacksonTupleModule;
import us.kbase.common.service.JsonClientException;
import us.kbase.common.service.JsonTokenStream;
import us.kbase.common.service.ServerException;
import us.kbase.common.service.UObject;

/**
 * <p>Original spec-file module name: ClusterService</p>
 * <pre>
 * </pre>
 */
public class ClusterServiceLocalClient {
    private File workDir;
    private AuthToken token;
    private ObjectMapper mapper;
    private File binDir = null;
    
    public ClusterServiceLocalClient(File workDir) {
        this.workDir = workDir;
        this.mapper = new ObjectMapper().registerModule(new JacksonTupleModule());
    }

    public AuthToken getToken() {
        return token;
    }
    
    public void setToken(AuthToken token) {
        this.token = token;
    }
    
    /**
     * Get directory containing shell-script running service side function.
     * If this directory is not set (it's null by default) then PATH variable
     * is used for running scripts.
     * @return directory containing shell-script running service
     */
    public File getBinDir() {
        return binDir;
    }
    
    /**
     * Set directory containing shell-script running service side function.
     * If this directory is not set (it's null by default) then PATH variable
     * is used for running scripts.
     * @param binDir directory containing shell-script running service
     */
    public void setBinDir(File binDir) {
        this.binDir = binDir;
    }
    
    /**
     * <p>Original spec-file function name: cluster_float_rows_scikit_kmeans</p>
     * <pre>
     * </pre>
     * @param   params   instance of type {@link us.kbase.clusterservice.ClusterFloatRowsScikitKmeansParams ClusterFloatRowsScikitKmeansParams}
     * @return   instance of type {@link us.kbase.clusterservice.ClusterResults ClusterResults}
     * @throws IOException if an IO exception occurs
     * @throws JsonClientException if a JSON RPC exception occurs
     */
    public ClusterResults clusterFloatRowsScikitKmeans(ClusterFloatRowsScikitKmeansParams params) throws IOException, JsonClientException {
        List<Object> args = new ArrayList<Object>();
        args.add(params);
        TypeReference<List<ClusterResults>> retType = new TypeReference<List<ClusterResults>>() {};
        List<ClusterResults> res = jsonrpcCall("ClusterService.cluster_float_rows_scikit_kmeans", args, retType, true, false);
        return res.get(0);
    }
    
    public void writeRequestData(String method, Object arg, OutputStream os, String id) 
            throws IOException {
        JsonGenerator g = mapper.getFactory().createGenerator(os, JsonEncoding.UTF8);
        g.writeStartObject();
        g.writeObjectField("params", arg);
        g.writeStringField("method", method);
        g.writeStringField("version", "1.1");
        g.writeStringField("id", id);
        g.writeObjectField("context", new HashMap<String, Object>());
        g.writeEndObject();
        g.close();
        os.flush();
    }
    
    public <ARG, RET> RET jsonrpcCall(String method, ARG arg, TypeReference<RET> cls, 
            boolean ret, boolean authRequired)
            throws IOException, JsonClientException {
        String id = ("" + Math.random()).replace(".", "");
        // Write real data into http output stream
        File inputFile = new File(workDir, "input.json");
        OutputStream os = new FileOutputStream(inputFile);
        writeRequestData(method, arg, os, id);
        os.close();
        String tokenString = authRequired ? token.toString() : null;
        File outputFile = new File(workDir, "output.json");
        // Run CLI function
        String serviceName = method.substring(0, method.indexOf('.'));
        String cmd = (binDir == null ? "" : (binDir.getAbsolutePath() + "/")) + "run_" + serviceName + "_async_job.sh";
        StringBuilder outSb = new StringBuilder();
        StringBuilder errSb = new StringBuilder();
        int exitCode = -1;
        try {
            if (authRequired) {
                exitCode = exec(workDir, outSb, errSb, cmd, inputFile.getAbsolutePath(), 
                        outputFile.getAbsolutePath(), tokenString);
            } else {
                exitCode = exec(workDir, outSb, errSb, cmd, inputFile.getAbsolutePath(), 
                        outputFile.getAbsolutePath());
            }
        } catch (Exception ex) {
            throw new JsonClientException("Error running service CLI for method '" + method + "': " + 
                    ex.getMessage(), ex);
        }
        if (exitCode != 0 && !(outputFile.exists() && outputFile.length() > 0)) {
            String msg = "Error running service CLI for method '" + method + "' " + 
                    "with exit code " + exitCode;
            if (outSb.length() > 0)
                msg += "\nOutput:\n" + outSb;
            if (errSb.length() > 0)
                msg += "\nErrors:\n" + errSb;
            throw new JsonClientException(msg);
        }
        // Parse response into json
        if (!outputFile.exists())
            throw new ServerException("Output file wasn't found", 0, "Unknown", null);
        JsonTokenStream jts = new JsonTokenStream(outputFile);
        Map<String, UObject> resp;
        try {
            resp = mapper.readValue(jts, new TypeReference<Map<String, UObject>>() {});
        } catch (JsonParseException ex) {
            FileInputStream is = new FileInputStream(outputFile);
            ByteArrayOutputStream headingBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[10000];
            while (headingBuffer.size() < 10000) {
                int len = is.read(buffer, 0, 10000 - headingBuffer.size());
                if (len < 0)
                    break;
                headingBuffer.write(buffer, 0, len);
            }
            is.close();
            headingBuffer.close();
            String receivedHeadingMessage = new String(headingBuffer.toByteArray(), Charset.forName("UTF-8"));
            if (receivedHeadingMessage.startsWith("{"))
                throw ex;
            throw new JsonClientException("Server response is not in JSON format:\n" + 
                    receivedHeadingMessage);
        } finally {
            jts.close();
        }
        if (resp.containsKey("error")) {
            Map<String, String> retError = resp.get("error").asClassInstance(new TypeReference<Map<String, String>>(){});
            String data = retError.get("data") == null ? retError.get("error") : retError.get("data");
            throw new ServerException(retError.get("message"),
                    new Integer(retError.get("code")), retError.get("name"),
                    data);
        } if (resp.containsKey("result")) {
            RET res = mapper.readValue(resp.get("result").getPlacedStream(), cls);
            return res;
        } else {
            throw new ServerException("An unknown server error occured", 0, "Unknown", null);
        }
    }
    
    private static int exec(File workDir, StringBuilder out, StringBuilder err, 
            String... cmdAndParams) throws Exception {
        Process proc = Runtime.getRuntime().exec(cmdAndParams, null, workDir);
        Thread inT = readInNewThread(proc.getInputStream(), out);
        Thread errT = readInNewThread(proc.getErrorStream(), err);
        inT.join();
        errT.join();
        return proc.waitFor();
    }
    
    private static Thread readInNewThread(final InputStream is, final StringBuilder out) {
        Thread ret = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(is, Charset.forName("utf-8")));
                    while (true) {
                        String l = br.readLine();
                        if (l == null)
                            break;
                        if (out != null)
                            out.append(l).append("\n");
                    }
                    br.close();
                } catch (Exception ignore) {}
            }
        });
        ret.start();
        return ret;
    }
}
