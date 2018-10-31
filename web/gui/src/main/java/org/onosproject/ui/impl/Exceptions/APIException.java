package org.onosproject.ui.impl.Exceptions;

public class APIException extends RuntimeException{

    private static final long serialVersionUID = 1L;

    private int errorCode;
    private String errorMessage;

    public APIException(int errorCode, String errorMessage) {
        super();
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String getMessage() {
        return "" + errorCode + "," + errorMessage;
    }
}
