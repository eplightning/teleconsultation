package org.eplight.medirc.server.user.auth;

import org.eplight.medirc.protocol.Basic;

/**
 * Created by EpLightning on 27.03.2016.
 */
public class SimpleAuthentication implements Authentication {

    @Override
    public int authenticate(Basic.Handshake msg) {
        // TODO: Implement
        return 0;
    }
}
