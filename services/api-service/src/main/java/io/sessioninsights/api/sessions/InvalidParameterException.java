package io.sessioninsights.api.sessions;

/** A query parameter is malformed; answered with {@code 400 {"error":"invalid_parameter","parameter":...}}. */
public class InvalidParameterException extends RuntimeException {

    private final String parameter;

    public InvalidParameterException(String parameter) {
        super("invalid parameter: " + parameter, null, false, false);
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }
}
