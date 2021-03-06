package org.visallo.web.auth;

import org.atmosphere.cpr.*;
import org.visallo.core.exception.VisalloException;
import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import org.visallo.web.CurrentUser;

import javax.crypto.SecretKey;
import javax.servlet.http.HttpServletRequest;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.visallo.core.config.Configuration.AUTH_TOKEN_PASSWORD;
import static org.visallo.core.config.Configuration.AUTH_TOKEN_SALT;

public class AuthTokenWebSocketInterceptor implements AtmosphereInterceptor {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(AuthTokenWebSocketInterceptor.class);

    private SecretKey tokenSigningKey;

    @Override
    public void configure(AtmosphereConfig config) {
        String keyPassword = config.getInitParameter(AUTH_TOKEN_PASSWORD);
        checkNotNull(keyPassword, "AtmosphereConfig init parameter '" + AUTH_TOKEN_PASSWORD + "' was not set.");
        String keySalt = config.getInitParameter(AUTH_TOKEN_SALT);
        checkNotNull(keySalt, "AtmosphereConfig init parameter '" + AUTH_TOKEN_SALT + "' was not set.");

        try {
            tokenSigningKey = AuthToken.generateKey(keyPassword, keySalt);
        } catch (Exception e) {
            throw new VisalloException("Key generation failed", e);
        }
    }

    @Override
    public Action inspect(AtmosphereResource resource) {
        try {
            AtmosphereRequest request = resource.getRequest();
            AuthToken token = getAuthToken(request);

            if (token != null && !token.isExpired()) {
                setCurrentUser(request, token);
            }
        } catch (AuthTokenException e) {
            LOGGER.warn("Auth token signature verification failed", e);
            return Action.CANCELLED;
        }

        return Action.CONTINUE;
    }

    @Override
    public void postInspect(AtmosphereResource resource) {
        // noop
    }

    @Override
    public void destroy() {
        // noop
    }

    private AuthToken getAuthToken(AtmosphereRequest request) throws AuthTokenException {
        String cookieString = request.getHeader("cookie");

        if (cookieString != null) {
            int tokenCookieIndex = cookieString.indexOf(AuthTokenFilter.TOKEN_COOKIE_NAME);
            if (tokenCookieIndex > -1) {
                int equalsSeperatorIndex = cookieString.indexOf("=", tokenCookieIndex);
                int cookieSeparatorIndex = cookieString.indexOf(";", equalsSeperatorIndex);
                if (cookieSeparatorIndex < 0) {
                    cookieSeparatorIndex = cookieString.length();
                }
                String tokenString = cookieString.substring(equalsSeperatorIndex + 1, cookieSeparatorIndex).trim();
                return AuthToken.parse(tokenString, tokenSigningKey);
            }
        }

        return null;
    }

    private void setCurrentUser(HttpServletRequest request, AuthToken token) {
        checkNotNull(token.getUserId(), "Auth token did not contain the userId");
        checkNotNull(token.getUsername(), "Auth token did not contain the username");
        CurrentUser.set(request, token.getUserId(), token.getUsername());
    }
}
