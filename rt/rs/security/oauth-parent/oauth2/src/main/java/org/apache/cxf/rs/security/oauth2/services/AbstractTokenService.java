/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.rs.security.oauth2.services;

import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.security.auth.x500.X500Principal;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.SecurityContext;

import org.apache.cxf.common.util.Base64Utility;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.jaxrs.utils.ExceptionUtils;
import org.apache.cxf.jaxrs.utils.JAXRSUtils;
import org.apache.cxf.rs.security.oauth2.common.Client;
import org.apache.cxf.rs.security.oauth2.common.ClientCredential;
import org.apache.cxf.rs.security.oauth2.common.OAuthError;
import org.apache.cxf.rs.security.oauth2.provider.ClientIdProvider;
import org.apache.cxf.rs.security.oauth2.provider.OAuthServiceException;
import org.apache.cxf.rs.security.oauth2.utils.AuthorizationUtils;
import org.apache.cxf.rs.security.oauth2.utils.OAuthConstants;
import org.apache.cxf.security.transport.TLSSessionInfo;

public class AbstractTokenService extends AbstractOAuthService {
    private boolean canSupportPublicClients;
    private boolean writeCustomErrors;
    private ClientIdProvider clientIdProvider;
    
    /**
     * Make sure the client is authenticated
     */
    protected Client authenticateClientIfNeeded(MultivaluedMap<String, String> params) {
        Client client = null;
        SecurityContext sc = getMessageContext().getSecurityContext();
        
        if (params.containsKey(OAuthConstants.CLIENT_ID)) {
            // Both client_id and client_secret are expected in the form payload
            client = getAndValidateClient(params.getFirst(OAuthConstants.CLIENT_ID),
                                          params.getFirst(OAuthConstants.CLIENT_SECRET));
        } else if (sc.getUserPrincipal() != null) {
            // Client has already been authenticated
            Principal p = sc.getUserPrincipal();
            if (p.getName() != null) {
                client = getClient(p.getName());
            } else {
                // Most likely a container-level authentication, possibly 2-way TLS, 
                // Check if the mapping between Principal and Client Id has been done in a filter
                String clientId = (String)getMessageContext().get(OAuthConstants.CLIENT_ID);
                if (StringUtils.isEmpty(clientId) && clientIdProvider != null) {
                    // Check Custom ClientIdProvider
                    clientId = clientIdProvider.getClientId(getMessageContext());
                }
                if (!StringUtils.isEmpty(clientId)) {
                    client = getClient(clientId);
                } 
            }
        } 
        
        if (client == null) {
            TLSSessionInfo tlsSessionInfo = 
                (TLSSessionInfo)getMessageContext().get(TLSSessionInfo.class.getName());
            if (tlsSessionInfo != null) {
                client = getClientFromTLSCertificates(sc, tlsSessionInfo);
            } else {
                // Basic Authentication is expected by default
                client = getClientFromBasicAuthScheme();
            }
        }
        
        if (client == null) {
            reportInvalidClient();
        }
        return client;
    }
    
    // Get the Client and check the id and secret
    protected Client getAndValidateClient(String clientId, String clientSecret) {
        Client client = getClient(clientId);
        if (clientSecret != null 
            && (client.getClientCredential().getType() == null 
            || ClientCredential.Type.PASSWORD != client.getClientCredential().getType())) {
            throw ExceptionUtils.toNotAuthorizedException(null, null);
        }
        if (canSupportPublicClients 
            && !client.isConfidential() 
            && client.getClientCredential() == null 
            && clientSecret == null) {
            return client;
        }
        if (clientSecret == null || client.getClientCredential() == null 
            || !client.getClientId().equals(clientId) 
            || !client.getClientCredential().getCredential().equals(clientSecret)) {
            throw ExceptionUtils.toNotAuthorizedException(null, null);
        }
        return client;
    }
    
    protected Client getClientFromBasicAuthScheme() {
        String[] parts = AuthorizationUtils.getAuthorizationParts(getMessageContext());
        if (OAuthConstants.BASIC_SCHEME.equalsIgnoreCase(parts[0])) {
            String[] authInfo = AuthorizationUtils.getBasicAuthParts(parts[1]);
            return getAndValidateClient(authInfo[0], authInfo[1]);
        } else {
            return null;
        }
    }
    
    protected Client getClientFromTLSCertificates(SecurityContext sc, TLSSessionInfo tlsSessionInfo) {
        Client client = null;
        if (tlsSessionInfo != null) {
            String authScheme = sc.getAuthenticationScheme();
            if (StringUtils.isEmpty(authScheme)) {
                // Pure 2-way TLS authentication
                String clientId = getClientIdFromTLSCertificates(sc, tlsSessionInfo);
                if (!StringUtils.isEmpty(clientId)) {
                    client = getClient(clientId);
                    // Validate the client identified from certificates
                    validateTwoWayTlsClient(sc, tlsSessionInfo, client);
                }
            } else if (OAuthConstants.BASIC_SCHEME.equalsIgnoreCase(authScheme)) {
                // Basic Authentication on top of 2-way TLS
                client = getClientFromBasicAuthScheme();    
            }
        }
        return client;
    }
    
    protected String getClientIdFromTLSCertificates(SecurityContext sc, TLSSessionInfo tlsInfo) {
        Certificate[] clientCerts = tlsInfo.getPeerCertificates();
        if (clientCerts != null && clientCerts.length > 0) {
            X500Principal x509Principal = ((X509Certificate)clientCerts[0]).getSubjectX500Principal();
            return x509Principal.getName();    
        }
        return null;
    }
    
    protected void validateTwoWayTlsClient(SecurityContext sc, TLSSessionInfo tlsSessionInfo, Client client) {
        ClientCredential.Type credType = client.getClientCredential().getType();
        if (credType != ClientCredential.Type.X509CERTIFICATE 
            && credType != ClientCredential.Type.PUBLIC_KEY) {
            reportInvalidClient();
        } else if (client.getClientCredential().getCredential() != null) {
            // Client has a Base64 encoded representation of the certificate loaded
            // so lets validate the TLS certificates
            compareCertificates(tlsSessionInfo, client.getClientCredential().getCredential(), credType);
        }
    }
    
    protected void compareCertificates(TLSSessionInfo tlsInfo, 
                                       String base64EncodedCert,
                                       ClientCredential.Type type) {
        Certificate[] clientCerts = tlsInfo.getPeerCertificates();
        try {
            X509Certificate cert = (X509Certificate)clientCerts[0];
            byte[] encodedKey = type == ClientCredential.Type.PUBLIC_KEY 
                ? cert.getPublicKey().getEncoded() : cert.getEncoded();
            byte[] clientKey = Base64Utility.decode(base64EncodedCert);
            if (Arrays.equals(encodedKey, clientKey)) {
                return;
            }
        } catch (Exception ex) {
            reportInvalidClient();
        }
    }
    
    
    
    protected Response handleException(OAuthServiceException ex, String error) {
        OAuthError customError = ex.getError();
        if (writeCustomErrors && customError != null) {
            return createErrorResponseFromBean(customError);
        } else {
            return createErrorResponseFromBean(new OAuthError(error));
        }
    }
    
    protected Response createErrorResponse(MultivaluedMap<String, String> params,
                                           String error) {
        return createErrorResponseFromBean(new OAuthError(error));
    }
    
    protected Response createErrorResponseFromBean(OAuthError errorBean) {
        return JAXRSUtils.toResponseBuilder(400).entity(errorBean).build();
    }
    
    /**
     * Get the {@link Client} reference
     * @param clientId the provided client id
     * @return Client the client reference 
     * @throws {@link javax.ws.rs.WebApplicationException} if no matching Client is found
     */
    protected Client getClient(String clientId) {
        if (clientId == null) {
            reportInvalidRequestError("Client ID is null");
            return null;
        }
        Client client = null;
        try {
            client = getValidClient(clientId);
        } catch (OAuthServiceException ex) {
            if (ex.getError() != null) {
                reportInvalidClient(ex.getError());
                return null;
            }
        }
        if (client == null) {
            reportInvalidClient();
        }
        return client;
    }
    
    protected void reportInvalidClient() {
        reportInvalidClient(new OAuthError(OAuthConstants.INVALID_CLIENT));
    }
    
    protected void reportInvalidClient(OAuthError error) {
        ResponseBuilder rb = JAXRSUtils.toResponseBuilder(401);
        throw ExceptionUtils.toNotAuthorizedException(null, 
            rb.type(MediaType.APPLICATION_JSON_TYPE).entity(error).build());
    }
    
    public void setCanSupportPublicClients(boolean support) {
        this.canSupportPublicClients = support;
    }

    public boolean isCanSupportPublicClients() {
        return canSupportPublicClients;
    }
    
    public void setWriteCustomErrors(boolean writeCustomErrors) {
        this.writeCustomErrors = writeCustomErrors;
    }

    public void setClientIdProvider(ClientIdProvider clientIdProvider) {
        this.clientIdProvider = clientIdProvider;
    }
}
