/*
 * DO NOT REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 ForgeRock Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */

package org.forgerock.restlet.ext.oauth2.provider;

import java.security.AccessController;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.iplanet.sso.SSOToken;
import com.sun.identity.security.AdminTokenAction;
import com.sun.identity.sm.ServiceConfig;
import com.sun.identity.sm.ServiceConfigManager;
import org.forgerock.openam.oauth2.OAuth2;
import org.forgerock.openam.oauth2.OAuth2Constants;
import org.forgerock.openam.oauth2.provider.OAuth2TokenStore;
import org.forgerock.openam.oauth2.utils.OAuth2Utils;
import org.forgerock.openam.oauth2.exceptions.OAuthProblemException;
import org.forgerock.restlet.ext.oauth2.consumer.AccessTokenValidator;
import org.forgerock.restlet.ext.oauth2.consumer.BearerAuthenticatorHelper;
import org.forgerock.restlet.ext.oauth2.consumer.BearerToken;
import org.forgerock.openam.oauth2.model.AccessToken;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.CacheDirective;
import org.restlet.data.Form;
import org.restlet.data.Method;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.ext.jackson.JacksonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.ClientResource;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.restlet.resource.ServerResource;
import org.forgerock.openam.oauth2.provider.Scope;

/**
 * A ValidationServerResource does ...
 * <p/>
 * 
 * <pre>
 * 
 * 
 * param parameters
 *  {
 *      "access_token":"1/fFBGRNJru1FQd44AzqT3Zg"
 *  }
 * 
 * return
 *  {
 *      "audience":"client_id",
 *      "user_id":"123456789",
 *      "scope":[
 *                  "https://read.openam.forgerock.org"
 *              ],
 *      "expires_in":436
 *  }
 * 
 * </pre>
 * 
 * @author Laszlo Hordos
 */
public class ValidationServerResource extends ServerResource implements
        AccessTokenValidator<BearerToken> {

    private OAuth2TokenStore tokenStore = null;
    private Reference validationServerRef;

    public ValidationServerResource() {
        this.validationServerRef = null;
    }

    public ValidationServerResource(Context context, Reference validationServerRef) {
        this.validationServerRef = validationServerRef;
        init(context, null, null);
    }

    /**
     * Set-up method that can be overridden in order to initialize the state of
     * the resource. By default it does nothing.
     * 
     * @see #init(org.restlet.Context, org.restlet.Request,
     *      org.restlet.Response)
     */
    protected void doInit() throws ResourceException {
        if (null != getRequest() && null != getContext()) {
            tokenStore = OAuth2Utils.getTokenStore(getContext());
            if (null == tokenStore) {
                throw new ResourceException(Status.SERVER_ERROR_INTERNAL,
                        "Missing required context attribute: " + OAuth2TokenStore.class.getName());
            }
        }
    }

    @Get("json")
    public Representation validate() throws ResourceException {
        getLogger().fine("In Validator resource");

        OAuthProblemException error = null;
        Map<String, Object> response = new HashMap<String, Object>();
        Scope scopeClass = null;

        try {
            Form call = getQuery();
            String token = call.getFirstValue(OAuth2.Params.ACCESS_TOKEN);

            if (null == token) {
                error =
                        OAuthProblemException.OAuthError.INVALID_REQUEST.handle(getRequest(),
                                "Missing access_token");
            } else {
                AccessToken t = tokenStore.readAccessToken(token);

                if (t == null) {
                    error = OAuthProblemException.OAuthError.INVALID_TOKEN.handle(getRequest());
                } else {
                    String pluginClass = getPluginClass(t.getRealm());
                    //instantiate plugin class
                    scopeClass = (Scope) Class.forName(pluginClass).newInstance();

                    //call plugin class init
                    getLogger().fine("In Validator resource - got token = " + t);

                    if (t.isExpired()) {
                        error = OAuthProblemException.OAuthError.INVALID_TOKEN.handle(getRequest());
                        getLogger().warning("Should response and refresh the token");
                    }
                    //call plugin class.process
                    Map <String, Object> returnMap = scopeClass.retrieveTokenInfoEndPoint(t);
                    if (error == null) {
                        response.putAll(t.convertToMap());
                        response.putAll(returnMap);
                    }

                }

            }
        } catch (OAuthProblemException e) {
            error = e;
        } catch (Exception e){
            error = OAuthProblemException.OAuthError.SERVER_ERROR.handle(getRequest(),
                    "Missing scope plugin class");
        }

        if (error != null) {
            response.putAll(error.getErrorMessage());
        }
        //call plugin class destroy for cleanup
        scopeClass.destroy();

        // Sets the no-store Cache-Control header
        getResponse().getCacheDirectives().add(CacheDirective.noCache());
        getResponse().getCacheDirectives().add(CacheDirective.noStore());
        return new JacksonRepresentation<Map>(response);
    }

    @Override
    public BearerToken verify(BearerToken token) throws OAuthProblemException {
        Reference reference = new Reference(validationServerRef);
        reference.addQueryParameter(OAuth2.Params.ACCESS_TOKEN, token.getAccessToken());
        ClientResource clientResource = new ClientResource(getContext(), reference);
        try {
            Request request = new Request(Method.GET, reference, null);

            // Actually handle the call
            Response response = clientResource.handleOutbound(request);

            // Throws OAuthProblemException
            Map remoteToken = BearerAuthenticatorHelper.extractToken(response);

            Object o = remoteToken.get(OAuth2.Token.OAUTH_EXPIRES_IN);
            Number expires_in = token.getExpiresIn();
            if (o instanceof Number) {
                expires_in = (Number) o;
            }

            o = remoteToken.get(OAuth2.Custom.AUDIENCE);
            String client_id = null;
            if (o instanceof String) {
                client_id = (String) o;
            }

            o = remoteToken.get(OAuth2.Custom.USER_ID);
            String username = null;
            if (o instanceof String) {
                username = (String) o;
            }

            o = remoteToken.get(OAuth2.Params.SCOPE);
            Set<String> scope = null;
            if (o instanceof Collection) {
                scope =
                        Collections.unmodifiableSet(new HashSet<String>(
                                (Collection<? extends String>) o));
            }
            return new BearerToken(token, expires_in, client_id, username, scope);
        } catch (OAuthProblemException e) {
            throw e;
        } catch (ResourceException e) {
            throw OAuthProblemException.OAuthError.ACCESS_DENIED.handle(null, e.getMessage());
        }
    }

    private String getPluginClass(String realm) throws OAuthProblemException {
        String pluginClass = null;
        try {
            SSOToken token = (SSOToken) AccessController.doPrivileged(AdminTokenAction.getInstance());
            ServiceConfigManager mgr = new ServiceConfigManager(token, OAuth2Constants.OAuth2ProviderService.NAME, OAuth2Constants.OAuth2ProviderService.VERSION);
            ServiceConfig scm = mgr.getOrganizationConfig(realm, null);
            Map<String, Set<String>> attrs = scm.getAttributes();
            pluginClass = attrs.get(OAuth2Constants.OAuth2ProviderService.SCOPE_PLUGIN_CLASS).iterator().next();
        } catch (Exception e) {
            throw new OAuthProblemException(Status.SERVER_ERROR_SERVICE_UNAVAILABLE.getCode(),
                    "Service unavailable", "Could not create underlying storage", null);
        }

        return pluginClass;
    }

}
