package com.mode.flamingo.test;

import io.restassured.filter.FilterContext;
import io.restassured.filter.session.SessionFilter;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import org.apache.commons.lang3.tuple.Pair;

public class ModeSessionFilter extends SessionFilter {

    public static final String SESSION_ID = "_session_id";
    public static final String TRACKING_TOKEN = "tracking_token";
    private String sessionId = null;
    private String trackingId = null;
    private String csrfToken;

    @Override
    public Response filter(FilterableRequestSpecification requestSpec, FilterableResponseSpecification responseSpec, FilterContext ctx) {
        if (sessionId != null && !requestSpec.getCookies().hasCookieWithName("_session_id")) {
            requestSpec.cookie(SESSION_ID, sessionId);
            requestSpec.cookie(TRACKING_TOKEN, trackingId);
        }
        if (csrfToken != null) {
            requestSpec.removeHeader("X-CSRF-Token");
            requestSpec.header("X-CSRF-Token", csrfToken);
        }
        Response response = ctx.next(requestSpec, responseSpec);
        if (response.getCookie(SESSION_ID) != null) {
            sessionId = response.getCookie(SESSION_ID);
            trackingId = response.getCookie(TRACKING_TOKEN);
        }
        if (response.getContentType().startsWith("text/html")) {
            Pair<String, String> csrfTokenDetails = SignInLocal.parseCsrfToken(response);
            if (csrfTokenDetails.getRight() != null) {
                csrfToken = csrfTokenDetails.getRight();
            }
        }

        return response;
    }
}
