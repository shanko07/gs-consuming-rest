package com.example.consumingrest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

@SpringBootApplication
public class ConsumingRestApplication {

    private static final Logger log = LoggerFactory.getLogger(ConsumingRestApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ConsumingRestApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }

    /**
     * The URL of the Polaris Instance you are working with
     */
    private static final String POLARIS_BASE_URL = System.getenv("POLARIS_BASE_URL");
    /**
     * The URL of the Code Dx Instance you are working with
     */
    private static final String CODEDX_BASE_URL = System.getenv("CODEDX_BASE_URL");

    /**
     * The Personal Access Token generated in Polaris.  Note it must come from an account with appropriate permissions
     */
    private static final String POLARIS_PAT_TOKEN = System.getenv("POLARIS_PAT_TOKEN");
    /**
     * The Personal Access Token generated in Code Dx.  Note it must come from an account with appropriate permissions
     */
    private static final String CODEDX_PAT_TOKEN = System.getenv("CODEDX_PAT_TOKEN");

    /**
     * This is a sample for fetching data from one application in Polaris.  You would want to loop over all
     * applications.  A Polaris application is simply a container for projects
     */
    private static final String POLARIS_APPLICATON_ID = System.getenv("POLARIS_APPLICATION_ID");
    /**
     * This is a sample for fetching data from one parent project in Code Dx.  You would want to loop over all parent
     * projects of interest.  This is the same concept as the Polaris application above
     */
    private static final String CODEDX_PROJECT_ID = System.getenv("CODEDX_PROJECT_ID");

    @Bean
    public CommandLineRunner run(RestTemplate restTemplate) throws Exception {
        return args -> {

            log.info("#################### Environment Variables ####################");
            StringJoiner sj = new StringJoiner(" ");
            sj
                    .add("POLARIS_BASE_URL").add(POLARIS_BASE_URL)
                    .add("CODEDX_BASE_URL").add(CODEDX_BASE_URL)
                    .add("POLARIS_PAT_TOKEN").add(POLARIS_PAT_TOKEN)
                    .add("CODEDX_PAT_TOKEN").add(CODEDX_PAT_TOKEN)
                    .add("POLARIS_APPLICATON_ID").add(POLARIS_APPLICATON_ID)
                    .add("CODEDX_PROJECT_ID").add(CODEDX_PROJECT_ID);

            log.info(sj.toString());

            /*********************  Quick xkcd test   *************************/
            XKCD quote = restTemplate.getForObject(
                    "https://xkcd.com/info.0.json", XKCD.class);
            log.info(quote.toString());


            /*********************  This is the Polaris section   *************************/
            ArrayList<String> polarisApplicationsOfInterest = new ArrayList<>();
            polarisApplicationsOfInterest.add(POLARIS_APPLICATON_ID);
            for (String app : polarisApplicationsOfInterest) {
                runPolarisTest(restTemplate, app);
            }


            /*********************  This is the Code Dx section   *************************/
            ArrayList<String> codeDxApplicationsOfInterest = new ArrayList<>();
            codeDxApplicationsOfInterest.add(CODEDX_PROJECT_ID);
            for (String app : codeDxApplicationsOfInterest) {
                runCodeDxTest(restTemplate, app);
            }


        };
    }

    private void runPolarisTest(RestTemplate restTemplate, String appId) {
        log.info("#################### Starting Polaris Tests ####################");
        // authenticate
        String jwt = authenticateToPolaris(restTemplate);

        JSONObject severityTaxonomy = getSeverityTaxonomyPolaris(restTemplate, jwt);
        HashMap<String, String> issueTypeNameToSeverity = new LinkedHashMap<>();
        // build a hash map first time through
        JSONArray taxas = severityTaxonomy.getJSONObject("taxonomy").getJSONArray("taxa");
        for (int j = 0; j < taxas.length(); j++) {
            JSONObject tax = taxas.getJSONObject(j);
            JSONArray issue_types = tax.getJSONArray("issue-types");
            String issueSeverity = tax.getString("id");
            for (int k = 0; k < issue_types.length(); k++) {
                issueTypeNameToSeverity.put(issue_types.getString(k), issueSeverity);
            }
        }

        AppAndProjectsPolaris aAndP = getApplicationProjectsPolaris(restTemplate, jwt, appId);
        JSONArray projectData = aAndP.projectList;
        String appName = aAndP.appName;
        log.info("fetching projects for application " + appId + " name " + appName);
        ArrayList<String> projectURLs = getProjectURLsPolaris(projectData);

        for (String projId : projectURLs) {
            String branchId = getBranchPolaris(restTemplate, jwt, projId);
            String projectName = getProjectNamePolaris(restTemplate, jwt, projId);

            if (branchId != null) {
                log.info("fetching issues for project " + projId + " name " + projectName);
                JSONObject issues = getIssuesPolaris(restTemplate, jwt, projId, branchId);

                JSONArray issueData = issues.getJSONArray("data");
                for (int i = 0; i < issueData.length(); i++) {
                    JSONObject finding = issueData.getJSONObject(i);

                    String issueTypeId = finding.getJSONObject("relationships").getJSONObject("issue-type")
                            .getJSONObject("data").getString("id");

                    String issueTypeName = getIssueTypeNamePolaris(restTemplate, jwt, issueTypeId);

                    String issueSeverity = issueTypeNameToSeverity.getOrDefault(issueTypeName, "Unknown");


                    String type = "Static Analysis";

                    JSONObject issueDeepData = getIssueDeepDataPolaris(restTemplate, jwt, finding.getString("id"),
                            projId, branchId);
                    Date mostRecentOpen = getMostRecentOpenDatePolaris(issueDeepData);

                    JSONObject triageStatus = getTriageStatus(restTemplate, jwt, projId, finding.getJSONObject(
                            "attributes").getString("issue-key"));

                    String status = "Unknown";

                    JSONArray triageCurrentValues =
                            triageStatus.getJSONObject("data").getJSONObject("attributes").getJSONArray("triage" +
                                    "-current-values");
                    for (int j = 0; j < triageCurrentValues.length(); j++) {
                        JSONObject val = triageCurrentValues.getJSONObject(j);
                        if (val.getString("attribute-semantic-id").equals("DISMISS")) {
                            Object obj = val.get("value");
                            if (obj == JSONObject.NULL) {
                                status = "NOT_DISMISSED";
                            } else {
                                status = (String) obj;
                            }
                            //log.info(obj.toString());
                        }
                    }

                    logFindingInfo(finding.getString("id"), issueTypeName, type, issueSeverity, mostRecentOpen, status);

                }
            }

        }

    }

    private void runCodeDxTest(RestTemplate restTemplate, String appId) {
        log.info("#################### Starting CodeDx Tests ####################");

        JSONArray r_json = getCodeDxChildProject(restTemplate, appId);

        String appName = getCodeDxProjectName(restTemplate, appId);
        log.info("fetching projects for application " + appId + " name " + appName);


        for (int i = 0; i < r_json.length(); i++) {
            JSONObject proj = r_json.getJSONObject(i);
            Integer p_id = (Integer) proj.get("id");
            String p_name = proj.getString("name");

            log.info("fetching all subfindings for project " + p_id + " with name " + p_name);


            JSONArray findingsArray = getCodeDxFindingsForProject(restTemplate, Integer.toString(p_id));

            for (int j = 0; j < findingsArray.length(); j++) {
                JSONObject finding = findingsArray.getJSONObject(j);


                String severity = finding.getJSONObject("severity").getString("name");
                String toolCategory = finding.getJSONObject("detectionMethod").getString("name");
                String firstSeenDateString = finding.getString("firstSeenOn");
                int findingId = finding.getInt("id");
                String type = finding.getJSONObject("descriptor").getString("name");
                String status = finding.getString("statusName");


                DateFormat mdyDf = new SimpleDateFormat("MM/dd/yyyy");
                try {
                    Date firstSeenDate = mdyDf.parse(firstSeenDateString);
                    logFindingInfo(Integer.toString(findingId), type, toolCategory, severity, firstSeenDate, status);
                } catch (ParseException e) {
                    log.error("failed to parse date");
                }
            }


        }
    }

    private void logFindingInfo(String findingId, String type, String toolCategory, String severity,
                                Date firstSeenDate, String triage) {
        DateFormat ymdDf = new SimpleDateFormat("yyyy-MM-dd");

        String fid = findingId;
        if (fid.length() > 4) {
            fid = fid.substring(0, 5) + "...";
        }
        log.info("Finding number " + fid + " rule " + type
                + " from " + toolCategory + " with severity " + severity + " first seen "
                + ymdDf.format(firstSeenDate) + " status " + triage);
    }

    private String authenticateToPolaris(RestTemplate restTemplate) {
        HttpHeaders headers = new HttpHeaders();

        MultiValueMap<String, String> auth_map = new LinkedMultiValueMap<>();
        auth_map.add("accesstoken", POLARIS_PAT_TOKEN);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(auth_map,
                headers);


        PolarisAuth auth_response = restTemplate.postForObject(
                POLARIS_BASE_URL + "/api/auth/v1/authenticate", request, PolarisAuth.class);

        return auth_response.getJwt();
    }

    static class AppAndProjectsPolaris {
        JSONArray projectList;
        String appName;

        AppAndProjectsPolaris(JSONArray pl, String an) {
            projectList = pl;
            appName = an;
        }

    }

    private AppAndProjectsPolaris getApplicationProjectsPolaris(RestTemplate restTemplate, String jwt,
                                                                String applicationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwt);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("application-id", applicationId);

        HttpEntity<MultiValueMap<String, String>> appRequestEntity =
                new HttpEntity<>(params, headers);

        ResponseEntity<String> app_response = restTemplate.exchange(
                POLARIS_BASE_URL + "/api/common/v0/applications/" + applicationId, HttpMethod.GET, appRequestEntity,
                String.class);

        String app_info = app_response.getBody();
        JSONObject json = new JSONObject(app_info);

        JSONArray projects_list =
                json.getJSONObject("data").getJSONObject("relationships").getJSONObject("projects").getJSONArray(
                        "data");
        String appName = json.getJSONObject("data").getJSONObject("attributes").getString("name");
        return new AppAndProjectsPolaris(projects_list, appName);
    }

    private ArrayList<String> getProjectURLsPolaris(JSONArray projects_list) {
        ArrayList<String> project_urls = new ArrayList<>();
        for (int i = 0; i < projects_list.length(); i++) {
            project_urls.add(projects_list.getJSONObject(i).getString("id"));
        }

        return project_urls;
    }

    private String getProjectNamePolaris(RestTemplate restTemplate, String jwt, String projectId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwt);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("project-id", projectId);

        HttpEntity<MultiValueMap<String, String>> appRequestEntity =
                new HttpEntity<>(params, headers);

        ResponseEntity<String> app_response = restTemplate.exchange(
                POLARIS_BASE_URL + "/api/common/v0/projects/" + projectId, HttpMethod.GET, appRequestEntity,
                String.class);

        String app_info = app_response.getBody();
        JSONObject json = new JSONObject(app_info);

        return json.getJSONObject("data").getJSONObject("attributes").getString("name");
    }

    private String getBranchPolaris(RestTemplate restTemplate, String jwt, String projectId) {
        String url = POLARIS_BASE_URL + "/api/common/v0/projects/" + projectId + "/relationships/branches";

        HttpHeaders proj_headers = new HttpHeaders();
        proj_headers.set("Authorization", "Bearer " + jwt);

        String urlBuilt = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("page[limit]", 500)
                .queryParam("page[offset]", 0)
                .buildAndExpand()
                .toUriString();

        HttpEntity<MultiValueMap<String, String>> projRequestEntity =
                new HttpEntity<>(new LinkedMultiValueMap<>(), proj_headers);

        ResponseEntity<String> proj_response = restTemplate.exchange(
                urlBuilt, HttpMethod.GET, projRequestEntity, String.class);


        String responseString = proj_response.getBody();
        JSONObject responseJson = new JSONObject(responseString);
        String branchId = null;
        try {
            branchId = responseJson.getJSONArray("data").getJSONObject(0).getString("id");
        } catch (Exception e) {
            log.error("failed to extract a branch for this project " + projectId);
        }

        return branchId;
    }

    private JSONObject getIssuesPolaris(RestTemplate restTemplate, String jwt, String projectId, String branchId) {
        String issues_url = POLARIS_BASE_URL + "/api/query/v1/issues";

        HttpHeaders proj_issue_headers = new HttpHeaders();
        proj_issue_headers.set("Authorization", "Bearer " + jwt);
        proj_issue_headers.set("accept", "application/vnd.api+json");

        String urlBuilt = UriComponentsBuilder.fromHttpUrl(issues_url)
                .queryParam("project-id", projectId)
                .queryParam("branch-id", branchId)
                .queryParam("page[limit]", 1000)
                .queryParam("page[offset]", 0)
                .buildAndExpand()
                .toUriString();

        HttpEntity<MultiValueMap<String, String>> projIssueRequestEntity =
                new HttpEntity<>(new LinkedMultiValueMap<>(), proj_issue_headers);

        ResponseEntity<String> proj_issue_response = restTemplate.exchange(
                urlBuilt, HttpMethod.GET, projIssueRequestEntity, String.class);

        String pi_response = proj_issue_response.getBody();
        return new JSONObject(pi_response);

    }

    private JSONObject getSeverityTaxonomyPolaris(RestTemplate restTemplate, String jwt) {
        String url = POLARIS_BASE_URL + "/api/taxonomy/v0/taxonomies";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwt);
        headers.set("Accept", "application/json");
        headers.set("Content-Type", "application/json");

        String urlBuilt = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("page[limit]", 1000)
                .queryParam("page[offset]", 0)
                .buildAndExpand()
                .toUriString();


        HttpEntity<MultiValueMap<String, String>> requestEntity =
                new HttpEntity<>(new LinkedMultiValueMap<>(), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                urlBuilt, HttpMethod.GET, requestEntity, String.class);

        String rString = response.getBody();
        JSONObject rJSON = new JSONObject(rString);
        JSONArray taxonomies = rJSON.getJSONArray("data");
        for (int i = 0; i < taxonomies.length(); i++) {
            JSONObject taxonomy = taxonomies.getJSONObject(i);
            String type = taxonomy.getString("taxonomy-type");
            if (type.equals("severity")) {
                return taxonomy;
            }
        }

        return null;

    }

    private String getIssueTypeNamePolaris(RestTemplate restTemplate, String jwt, String issueTypeId) {
        String url = POLARIS_BASE_URL + "/api/query/v0/issue-types/" + issueTypeId;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwt);
        headers.set("Accept", "application/vnd.api+json");
        headers.set("Content-Type", "application/json");

        String urlBuilt = UriComponentsBuilder.fromHttpUrl(url)
                .buildAndExpand()
                .toUriString();


        HttpEntity<MultiValueMap<String, String>> request =
                new HttpEntity<>(new LinkedMultiValueMap<>(), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                urlBuilt, HttpMethod.GET, request, String.class);

        String responseString = response.getBody();
        JSONObject responseJson = new JSONObject(responseString);
        return responseJson.getJSONObject("data").getJSONObject("attributes").getString("issue-type-id");

    }

    private JSONObject getIssueDeepDataPolaris(RestTemplate restTemplate, String jwt, String issueId,
                                               String projectId, String branchId) {
        String url = POLARIS_BASE_URL + "/api/query/v1/issues/" + issueId;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwt);
        headers.set("Accept", "application/vnd.api+json");
        headers.set("Content-Type", "application/json");

        String urlBuilt = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("project-id", projectId)
                .queryParam("branch-id", branchId)
                .buildAndExpand()
                .toUriString();


        HttpEntity<MultiValueMap<String, String>> requestEntity =
                new HttpEntity<>(new LinkedMultiValueMap<>(), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                urlBuilt, HttpMethod.GET, requestEntity, String.class);

        String rString = response.getBody();
        return new JSONObject(rString);

    }

    private Date getMostRecentOpenDatePolaris(JSONObject issueDeepData) {
        JSONArray included = issueDeepData.getJSONArray("included");

        TreeSet<Date> openDates = new TreeSet<>();

        for (int i = 0; i < included.length(); i++) {
            JSONObject item = included.getJSONObject(i);
            String itemType = item.getString("type");
            if (itemType.equals("transition")) {
                String transitionType = item.getJSONObject("attributes").getString("transition-type");
                if (transitionType.equals("opened")) {
                    TimeZone tz = TimeZone.getTimeZone("UTC");
                    DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'"); // Quoted "Z" to
                    // indicate UTC, no timezone offset
                    df.setTimeZone(tz);
                    try {
                        String dateString = item.getJSONObject("attributes").getString("transition-date");
                        Date openDate = df.parse(dateString);
                        openDates.add(openDate);
                    } catch (ParseException e) {
                        log.error("couldn't parse date");
                    }
                }
            }
        }

        return openDates.last();
    }

    private JSONObject getTriageStatus(RestTemplate restTemplate, String jwt, String projectId, String issueKey) {
        String url =
                POLARIS_BASE_URL + "/api/triage/v1/triage-current/" + "project-id:" + projectId + ":issue-key:" + issueKey;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwt);
        headers.set("Accept", "application/vnd.api+json");
        headers.set("Content-Type", "application/json");

        String urlBuilt = UriComponentsBuilder.fromHttpUrl(url)
                .buildAndExpand()
                .toUriString();


        HttpEntity<MultiValueMap<String, String>> requestEntity =
                new HttpEntity<>(new LinkedMultiValueMap<>(), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                urlBuilt, HttpMethod.GET, requestEntity, String.class);

        String rString = response.getBody();
        return new JSONObject(rString);
    }

    private String getCodeDxProjectName(RestTemplate restTemplate, String projectId) {
        String url = CODEDX_BASE_URL + "/codedx/api/projects/" + projectId;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + CODEDX_PAT_TOKEN);
        headers.set("Content-Type", "application/json");
        headers.set("Accept", "application/json");

        String urlBuilt = UriComponentsBuilder.fromHttpUrl(url)
                .buildAndExpand()
                .toUriString();

        HttpEntity<String> request = new HttpEntity<>("{}", headers);

        ResponseEntity<String> response = restTemplate.exchange(
                urlBuilt, HttpMethod.GET, request, String.class);


        String responseString = response.getBody();
        return new JSONObject(responseString).getString("name");
    }

    private JSONArray getCodeDxChildProject(RestTemplate restTemplate, String parentProjectId) {
        String url = CODEDX_BASE_URL + "/codedx/api/projects/query";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + CODEDX_PAT_TOKEN);
        headers.set("Content-Type", "application/json");
        headers.set("Accept", "application/json");

        String urlBuilt = UriComponentsBuilder.fromHttpUrl(url)
                .buildAndExpand()
                .toUriString();

        HttpEntity<String> request = new HttpEntity<>("{\"offset\": 100, \"limit\": 0, \"filter\": " +
                "{\"parentId\": " + parentProjectId + "}}", headers);

        ResponseEntity<String> response = restTemplate.exchange(
                urlBuilt, HttpMethod.POST, request, String.class);


        String responseString = response.getBody();
        return new JSONArray(responseString);

    }

    private JSONArray getCodeDxFindingsForProject(RestTemplate restTemplate, String projectId) {
        String url = CODEDX_BASE_URL + "/codedx/api/projects/" + "d" + projectId + "/findings/table";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + CODEDX_PAT_TOKEN);
        headers.set("Content-Type", "application/json");
        headers.set("Accept", "application/json");


        String builtURL = UriComponentsBuilder.fromHttpUrl(url)
                .buildAndExpand()
                .toUriString();


        HttpEntity<String> request = new HttpEntity<>("{}", headers);

        ResponseEntity<String> response = restTemplate.exchange(
                builtURL, HttpMethod.POST, request, String.class);


        String responseString = response.getBody();
        return new JSONArray(responseString);

    }
}
