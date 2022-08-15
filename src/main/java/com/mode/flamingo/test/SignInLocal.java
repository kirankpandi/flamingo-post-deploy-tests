package com.mode.flamingo.test;

import io.restassured.authentication.FormAuthConfig;
import io.restassured.filter.session.SessionFilter;
import io.restassured.internal.util.IOUtils;
import io.restassured.path.json.JsonPath;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.*;
import static io.restassured.path.json.JsonPath.*;

public class SignInLocal {

    public static Pair<String, String> parseCsrfToken(Response response) {
        XmlPath html = new XmlPath(XmlPath.CompatibilityMode.HTML, response.asString());
        String csrfParam = html.getString("html.depthFirst().grep { it.name() == 'meta' && it.@name == 'csrf-param' }.collect { it.@content }");
        String csrfToken = html.getString("html.depthFirst().grep { it.name() == 'meta' && it.@name == 'csrf-token' }.collect { it.@content }");
        return Pair.of(csrfParam, csrfToken);
    }

    public static void main(String[] args) throws IOException {
        baseURI = "http://goat.farm";
        port = 3000;

        ModeSessionFilter sessionFilter = new ModeSessionFilter();
        given().log().all().
        given().filter(sessionFilter).filter(new ModeLoginFilter("kiran@modeanalytics.com", "Password123")).
        when().
                get("/").
        then().
                statusCode(200);
        createReportAndRunSelect(sessionFilter);
    }

    private static void createReportAndRunSelect(ModeSessionFilter sessionFilter) throws IOException {
        String reportId = createReport(sessionFilter);
        Pair<String, String> runDetails = executeQuery(sessionFilter, reportId, "select * from SNOWFLAKE_SAMPLE_DATA.TPCH_SF1000.ORDERS", "2");
        Pair<String, String> switchPlanDetails = waitAndGetSwitchPlan(sessionFilter, reportId, runDetails.getLeft(), runDetails.getRight());
        String switchPlanTemplate = new String(IOUtils.toByteArray(SignInLocal.class.getResourceAsStream("switchPlan.json")), StandardCharsets.UTF_8);
        executeSelect(sessionFilter, switchPlanDetails.getLeft(),
                switchPlanTemplate.replace("\"%switch_plan_name%", switchPlanDetails.getLeft()).replace("%dataset_id%", switchPlanDetails.getRight()));
    }

    private static String createReport(ModeSessionFilter sessionFilter) {
        RequestSpecification requestSpec = given().log().all().filter(sessionFilter);
        requestSpec.get("/editor/mode/reports/new");
        Response response = given().log().all().filter(sessionFilter).get("/api/kiranpandi?embed[new_report]=1&embed[preference]=1&embed[spaces]=1&embed[new_space]=1&embed[new_definition]=1&embed[organization_features]=1");
        System.out.println(response.asString());
        response = given().log().all().filter(sessionFilter).get("/api/mode?embed[new_report]=1&embed[spaces]=1&embed[new_space]=1&embed[new_definition]=1&embed[organization_features]=1");
        System.out.println(response.asString());

        Response createReportResponse = given().log().all().filter(sessionFilter).
                queryParams(Map.of("embed[new_report_pin]", 1, "embed[new_report_run]", 1, "embed[report_runs]", 1, "embed[report_pins]", 1,
                        "embed[queries]", 1, "embed[new_query]", 1, "embed[python_visualizations]", 1, "embed[report_theme]", 1, "embed[report_filters]", 1))
                .get("/api/mode/reports/new");
        createReportResponse.then().assertThat().statusCode(200);
        return from(createReportResponse.asString()).getString("token");
    }

    private static Pair<String, String> executeQuery(ModeSessionFilter sessionFilter, String reportId, String query, String datasetId) {
        Map<String, Integer> execQueryParams = new HashMap<>();
        execQueryParams.putAll(Map.of("embed[new_report_run_email]", 1, "embed[new_report_run_slack_message]", 1, "embed[query_runs]", 1,
                "embed[report][new_python_notebook]", 1, "embed[report][new_report_schedule]", 1, "embed[report][new_report_filter]", 1,
                "embed[report][new_report_subscription]", 1, "embed[report][new_embed_key]", 1, "embed[report][new_python_visualization]", 1,
                "embed[report][new_space_link]", 1));
        execQueryParams.putAll(Map.of("embed[report][report_filters]", 1, "embed[report][local_switch_views][switch_views][last_run_dataset]", 1,
                "embed[report][report_pins][pinned]", 1, "embed[report][queries][queries][new_chart]", 1, "embed[report][queries][queries][charts][charts][color_palette]",
                1, "embed[report][queries][queries][new_query_table]", 1, "embed[report][queries][queries][query_tables]", 1));
        Response execQueryResopnse = given().log().all().filter(sessionFilter).body(String.format("{\"report\":{\"name\":\"\",\"description\":\"\",\"type\":\"Report\",\"report_run\":{\"limit\":{\"selected\":false,\"value\":100}},\"queries[]\":[{\"create_query_run\":true,\"limit\":true,\"data_source_id\":%s,\"name\":\"Query 1\",\"raw_query\":\"%s\",\"mapping_id\":\"%s\",\"selected_query\":\"\",\"selected_run\":false}],\"layout\":\"<div class=\\\"mode-header embed-hidden\\\">\\n  <h1>{{ title }}</h1>\\n  <p>{{ description }}</p>\\n</div>\"}}",
                        datasetId, query, UUID.randomUUID())).
                contentType("application/json").
                post("/api/mode/reports/" + reportId + "/editor/runs");
        System.out.println(execQueryResopnse.asString());
        execQueryResopnse.then().assertThat().statusCode(200);
        JsonPath jsonResponse = from(execQueryResopnse.asString());
        return Pair.of(jsonResponse.getString("token"), jsonResponse.getString("_embedded.report._embedded.local_switch_views._embedded.switch_views[0].token"));
    }

    private static Pair<String, String> waitAndGetSwitchPlan(SessionFilter sessionFilter, String reportId, String runId, String switchPlanId) {
        boolean first = true;
        String status = null;

        RequestSpecification requestSpec = given().log().all().filter(sessionFilter);
        do {
            if(!first) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                }
            }
            Response response = requestSpec.get("/api/mode/reports/" + reportId + "/switch_views/" + switchPlanId + "/runs/" + runId);
            status = from(response.asString()).getString("state");
        } while("enqueued".equalsIgnoreCase(status));

        Response switchPlanResp = requestSpec.get("/api/mode/switch_views/" + switchPlanId, Map.of("report_run", runId, "embed[dataset_for_run_in_context][creator]",
                "1", "embed[dataset_for_run_in_context][result]", "1", "embed[switch_attrs]", "1", "embed[new_visualization]", "1",
                "embed[new_switch_attr]", "1"));

        switchPlanResp.then().assertThat().statusCode(200);
        JsonPath jsonResponse = from(switchPlanResp.asString());
        return Pair.of(jsonResponse.getString("name"), jsonResponse.getString("embedded.dataset_for_run_in_context.token"));
    }

    private static JsonPath executeSelect(SessionFilter sessionFilter, String datasetId, String switchPlan) {
        waitForDatasetLoad(sessionFilter, datasetId);
        Response selectResponse = given().log().all().filter(sessionFilter).
                body(switchPlan).
                post("/selects");
        selectResponse.then().assertThat().statusCode(200);
        return from(selectResponse.asString());
    }

    private static void waitForDatasetLoad(SessionFilter sessionFilter, String datasetId) {
        boolean first = false;
        String state = null;
        do {
            if(!first) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                }
            }
            Response response = given().log().all().filter(sessionFilter).
                    get("/datasets/" + datasetId);
            state = from(response.asString()).getString("state");
        } while("ALLOCATED".equalsIgnoreCase(state));
    }

    private static FormAuthConfig formAuthConfig() {
        return new FormAuthConfig("/auth/identity/callback","auth_key",
                "password");
    }
}

