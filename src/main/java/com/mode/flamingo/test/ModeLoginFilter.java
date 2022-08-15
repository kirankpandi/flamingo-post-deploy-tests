package com.mode.flamingo.test;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;

public class ModeLoginFilter implements Filter {

    private final String username;
    private final String password;

    private String csrfParam;
    private String csrfToken;

    public ModeLoginFilter(String username, String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    public Response filter(FilterableRequestSpecification requestSpec, FilterableResponseSpecification responseSpec, FilterContext ctx) {
        List<Filter> sessionFilter = requestSpec.getDefinedFilters().stream().filter(filter -> !ModeLoginFilter.class.isAssignableFrom(filter.getClass())).collect(Collectors.toList());
        Response signinPage = given().filters(sessionFilter).auth().none().get("/signin");
        Pair<String, String> csrfDetails = SignInLocal.parseCsrfToken(signinPage);
        csrfParam = csrfDetails.getLeft();
        csrfToken = csrfDetails.getRight();
        Response loginResponse = given().filters(sessionFilter).auth().none().and()
                .formParams("auth_key", username, "password", password, csrfParam, csrfToken)
                .post("/auth/identity/callback");
        if (loginResponse.statusCode() >= 400) {
            throw new IllegalArgumentException(loginResponse.asString());
        }
        requestSpec.cookies(loginResponse.getCookies());
        return ctx.next(requestSpec, responseSpec);
    }

}
