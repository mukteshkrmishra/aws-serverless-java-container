/*
 * Copyright 2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.amazonaws.serverless.proxy.internal.servlet;

import com.amazonaws.serverless.proxy.ExceptionHandler;
import com.amazonaws.serverless.proxy.internal.LambdaContainerHandler;
import com.amazonaws.serverless.proxy.RequestReader;
import com.amazonaws.serverless.proxy.ResponseWriter;
import com.amazonaws.serverless.proxy.SecurityContextWriter;
import com.amazonaws.services.lambda.runtime.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Abstract extension of the code <code>LambdaContainerHandler</code> object that adds protected variables for the
 * <code>ServletContext</code> and <code>FilterChainManager</code>. This object should be extended by the framework-specific
 * implementations that want to support the servlet 3.1 specs.
 *
 * Because Lambda only allows one event per container at a time, this object also acts as the <code>RequestDispatcher</code>
 * @param <RequestType> The expected request object. This is the model class that the event JSON is de-serialized to
 * @param <ResponseType> The expected Lambda function response object. Responses from the container will be written to this model object
 * @param <ContainerRequestType> The request type for the wrapped Java container
 * @param <ContainerResponseType> The response or response writer type for the wrapped Java container
 */
public abstract class AwsLambdaServletContainerHandler<RequestType, ResponseType,
                                                        ContainerRequestType extends HttpServletRequest,
                                                        ContainerResponseType extends HttpServletResponse>
        extends LambdaContainerHandler<RequestType, ResponseType, ContainerRequestType, ContainerResponseType> {

    //-------------------------------------------------------------
    // Variables - Private
    //-------------------------------------------------------------

    protected ServletContext servletContext;
    private Logger log = LoggerFactory.getLogger(AwsLambdaServletContainerHandler.class);

    //-------------------------------------------------------------
    // Variables - Protected
    //-------------------------------------------------------------
    protected StartupHandler startupHandler;
    private FilterChainManager<AwsServletContext> filterChainManager;


    //-------------------------------------------------------------
    // Constructors
    //-------------------------------------------------------------

    protected AwsLambdaServletContainerHandler(RequestReader<RequestType, ContainerRequestType> requestReader,
                                     ResponseWriter<ContainerResponseType, ResponseType> responseWriter,
                                     SecurityContextWriter<RequestType> securityContextWriter,
                                     ExceptionHandler<ResponseType> exceptionHandler) {
        super(requestReader, responseWriter, securityContextWriter, exceptionHandler);
    }

    //-------------------------------------------------------------
    // Methods - Public
    //-------------------------------------------------------------

    /**
     * Fowards a request to the existing framework container. This is called by the <code>AwsProxyRequestDispatcher</code> object
     * @param servletRequest The modified request object with the new request path
     * @param servletResponse The original servlet response
     * @throws ServletException
     * @throws IOException
     */
    public void forward(ContainerRequestType servletRequest, ContainerResponseType servletResponse)
            throws ServletException, IOException {
        try {
            handleRequest(servletRequest, servletResponse, lambdaContext);
        } catch (Exception e) {
            log.error("Could not forward request", e);
            throw new ServletException(e);
        }
    }


    /**
     * Includes a request to the existing framework container. This is called by the <code>AwsProxyRequestDispatcher</code> object
     * @param servletRequest The modified request object with the new request path
     * @param servletResponse The original servlet response
     * @throws ServletException
     * @throws IOException
     */
    public void include(ContainerRequestType servletRequest, ContainerResponseType servletResponse)
            throws ServletException, IOException {
        try {
           handleRequest(servletRequest, servletResponse, lambdaContext);
        } catch (Exception e) {
            log.error("Could not include request", e);
            throw new ServletException(e);
        }
    }


    /**
     * You can use the <code>onStartup</code> to intercept the ServletContext as the Spring application is
     * initialized and inject custom values. The StartupHandler is called after the <code>onStartup</code> method
     * of the <code>LambdaSpringApplicationinitializer</code> implementation. For example, you can use this method to
     * add custom filters to the servlet context:
     *
     * <pre>
     * {@code
     *      handler = SpringLambdaContainerHandler.getAwsProxyHandler(EchoSpringAppConfig.class);
     *      handler.onStartup(c -> {
     *      // the "c" parameter to this function is the initialized servlet context
     *      c.addFilter("CustomHeaderFilter", CustomHeaderFilter.class);
     *      });
     * }
     * </pre>
     * @param h A lambda expression that implements the <code>StartupHandler</code> functional interface
     */
    public void onStartup(final StartupHandler h) {
        startupHandler = h;
    }

    @Override
    protected void handleRequest(ContainerRequestType containerRequest, ContainerResponseType containerResponse, Context lambdaContext)
            throws Exception {
        // The servlet context should not be linked to a specific request object, only to the Lambda
        // context so we only set it once.
        // TODO: In the future, if we decide to support multiple servlets/contexts in an instance we only need to modify this method
        if (getServletContext() == null) {
            setServletContext(new AwsServletContext(this));
        }
    }


    //-------------------------------------------------------------
    // Methods - Getter/Setter
    //-------------------------------------------------------------

    /**
     * Returns the current ServletContext. If the framework implementation does not set the value for
     * servlet context this method will return null.
     * @return The initialized servlet context if the framework-specific implementation requires one, otherwise null
     */
    public ServletContext getServletContext() {
        return servletContext;
    }


    /**
     * Sets the ServletContext in the handler and initialized a new <code>FilterChainManager</code>
     * @param context An initialized ServletContext
     */
    protected void setServletContext(final ServletContext context) {
        servletContext = context;
        // We assume custom implementations of the RequestWriter for HttpServletRequest will reuse
        // the existing AwsServletContext object since it has no dependencies other than the Lambda context
        filterChainManager = new AwsFilterChainManager((AwsServletContext)servletContext);
    }

    protected FilterChain getFilterChain(ContainerRequestType req, Servlet servlet) {
        return filterChainManager.getFilterChain(req, servlet);
    }


    //-------------------------------------------------------------
    // Methods - Protected
    //-------------------------------------------------------------

    /**
     * Applies the filter chain in the request lifecycle
     * @param request The Request object. This must be an implementation of HttpServletRequest
     * @param response The response object. This must be an implementation of HttpServletResponse
     * @param servlet Servlet at the end of the chain (optional).
     * @throws IOException
     * @throws ServletException
     */
    protected void doFilter(ContainerRequestType request, ContainerResponseType response, Servlet servlet) throws IOException, ServletException {
        FilterChain chain = getFilterChain(request, servlet);
        log.debug("FilterChainHolder.doFilter {}", chain);
        chain.doFilter(request, response);
    }

    //-------------------------------------------------------------
    // Inner Class -
    //-------------------------------------------------------------

    public interface StartupHandler {

        //-------------------------------------------------------------
        // Methods - Public
        //-------------------------------------------------------------

        void onStartup(ServletContext context);
    }
}
