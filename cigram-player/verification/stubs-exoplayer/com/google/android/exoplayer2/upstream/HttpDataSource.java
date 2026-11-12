package com.google.android.exoplayer2.upstream;
public interface HttpDataSource { class HttpDataSourceException extends java.io.IOException {} final class InvalidResponseCodeException extends HttpDataSourceException { public final int responseCode; public InvalidResponseCodeException(int c){responseCode=c;} } }
