package controllers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.horstmann.codecheck.Util;

import models.CodeCheck;
import play.Logger;
import play.libs.Json;
import play.libs.concurrent.HttpExecution;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

public class Check extends Controller {
    private CodecheckExecutionContext ccec; 
    @Inject private CodeCheck codeCheck;
    
    
    // Classic HTML report, used in Core Java for the Impatient 2e
    public CompletableFuture<Result> checkHTML(Http.Request request) throws IOException, InterruptedException {
        Map<String, String[]> params = request.body().asFormUrlEncoded();
        return CompletableFuture.supplyAsync(() -> {
            try {
                String ccid = null;
                String repo = "ext";
                String problem = "";
                Map<Path, String> submissionFiles = new TreeMap<>();
                
                for (String key : params.keySet()) {
                    String value = params.get(key)[0];
                    if (key.equals("repo"))
                        repo = value;
                    else if (key.equals("problem"))
                        problem = value;
                    else if (key.equals("ccid")) // TODO: For testing of randomization?
                        ccid = value;
                    else
                        submissionFiles.put(Paths.get(key), value);
                }
                if (ccid == null) { 
                    Optional<Http.Cookie> ccidCookie = request.getCookie("ccid");
                    ccid = ccidCookie.map(Http.Cookie::value).orElse(com.horstmann.codecheck.Util.createPronouncableUID());
                }
                long startTime = System.nanoTime();         
                String report = codeCheck.run("html", repo, problem, ccid, submissionFiles).getText();
                double elapsed = (System.nanoTime() - startTime) / 1000000000.0;
                if (report == null || report.length() == 0) {
                    report = String.format("Timed out after %5.0f seconds\n", elapsed);
                }
                
                Http.Cookie newCookie = models.Util.buildCookie("ccid", ccid);
                return ok(report).withCookies(newCookie).as("text/html");
            }
            catch (Exception ex) {
                return internalServerError(Util.getStackTrace(ex));
            }
        }, HttpExecution.fromThread((Executor) ccec) /* ec.current() */);                        
    }
            
    // From JS UI
    public CompletableFuture<Result> checkNJS(Http.Request request) throws IOException, InterruptedException  {
        Map<String, String[]> params;
        if ("application/x-www-form-urlencoded".equals(request.contentType().orElse(""))) 
            params = request.body().asFormUrlEncoded();
        else if ("application/json".equals(request.contentType().orElse(""))) {
            params = new HashMap<>();
            JsonNode json = request.body().asJson();
            Iterator<Entry<String, JsonNode>> iter = json.fields();
            while (iter.hasNext()) {
                Entry<String, JsonNode> entry = iter.next();
                params.put(entry.getKey(), new String[] { entry.getValue().asText() });         
            };
        }
        else 
            params = request.queryString();
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                String ccid = null;
                String repo = "ext";
                String problem = null;
                String reportType = "NJS";
                String scoreCallback = null;
                StringBuilder requestParams = new StringBuilder();
                ObjectNode studentWork = JsonNodeFactory.instance.objectNode();
                Map<Path, String> submissionFiles = new TreeMap<>();
                Map<Path, byte[]> reportZipFiles = new TreeMap<>();
                for (String key : params.keySet()) {
                    String value = params.get(key)[0];
                    
                    if (requestParams.length() > 0) requestParams.append(", ");
                    requestParams.append(key);
                    requestParams.append("=");
                    int nl = value.indexOf('\n');
                    if (nl >= 0) {
                        requestParams.append(value.substring(0, nl));  
                        requestParams.append("...");
                    }
                    else requestParams.append(value);
                    
                    if ("repo".equals(key)) repo = value;
                    else if ("problem".equals(key)) problem = value;
                    else if ("scoreCallback".equals(key)) scoreCallback = value;
                    else if ("ccid".equals(key)) ccid = value; // TODO: For testing of randomization? 
                    else {
                        Path p = Paths.get(key);                    
                        submissionFiles.put(p, value);
                        reportZipFiles.put(p, value.getBytes(StandardCharsets.UTF_8));
                        studentWork.put(key, value);
                    }
                }
                if (ccid == null) { 
                    Optional<Http.Cookie> ccidCookie = request.getCookie("ccid");
                    ccid = ccidCookie.map(Http.Cookie::value).orElse(com.horstmann.codecheck.Util.createPronouncableUID());
                };              
                //Logger.of("com.horstmann.codecheck.check").info("checkNJS: " + requestParams);
                //TODO last param should be submissionDir
                String report = codeCheck.run(reportType, repo, problem, ccid, submissionFiles).getText();
                ObjectNode result = (ObjectNode) Json.parse(report);
                String reportHTML = result.get("report").asText();
                reportZipFiles.put(Paths.get("report.html"), reportHTML.getBytes(StandardCharsets.UTF_8));

                byte[] reportZipBytes = codeCheck.signZip(reportZipFiles);
                    
                // TODO Need to sign
                String reportZip = Base64.getEncoder().encodeToString(reportZipBytes); 
                
                //TODO: Score callback no longer used from LTIHub. Does Engage use it?
                if (scoreCallback != null) {
                    if (scoreCallback.startsWith("https://")) 
                        scoreCallback = "http://" + scoreCallback.substring("https://".length()); // TODO: Fix
                    
                    //TODO: Add to result the student submissions
                    ObjectNode augmentedResult = result.deepCopy();
                    augmentedResult.set("studentWork", studentWork);
                    
                    String resultText = Json.stringify(augmentedResult);
                    Logger.of("com.horstmann.codecheck.lti").info("Request: " + scoreCallback + " " + resultText);
                    String response = com.horstmann.codecheck.Util.httpPost(scoreCallback, resultText, "application/json");
                    Logger.of("com.horstmann.codecheck.lti").info("Response: " + response);
                }
                
                result.put("zip", reportZip);
                Http.Cookie newCookie = models.Util.buildCookie("ccid", ccid);
                return ok(result).withCookies(newCookie).as("application/json");
            } catch (Exception ex) {
                return internalServerError(Util.getStackTrace(ex));
            }
        }, HttpExecution.fromThread((Executor) ccec) /* ec.current() */);           
    }
}
