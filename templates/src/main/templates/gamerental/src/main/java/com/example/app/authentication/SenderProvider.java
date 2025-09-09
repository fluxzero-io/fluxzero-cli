package com.example.app.authentication;

import com.example.app.user.UserProfile;
import io.fluxcapacitor.common.MessageType;
import io.fluxcapacitor.javaclient.FluxCapacitor;
import io.fluxcapacitor.javaclient.common.HasMessage;
import io.fluxcapacitor.javaclient.common.serialization.DeserializingMessage;
import io.fluxcapacitor.javaclient.tracking.handling.authentication.AbstractUserProvider;
import io.fluxcapacitor.javaclient.tracking.handling.authentication.User;

public class SenderProvider extends AbstractUserProvider {

    public SenderProvider() {
        super(Sender.class);
    }

    @Override
    public User fromMessage(HasMessage message) {
        if (message instanceof DeserializingMessage dm && dm.getMessageType() == MessageType.WEBREQUEST) {
            //for demo purposes, let's assume that everyone sending web requests is admin. Don't use this in the real world! :P
            return Sender.system;
        }
        return super.fromMessage(message);
    }

    @Override
    public User getUserById(Object userId) {
        UserProfile userProfile = FluxCapacitor.loadAggregate(userId, UserProfile.class).get();
        return userProfile == null ? null : Sender.builder().userId(userProfile.userId())
                .userRole(userProfile.role()).build();
    }

    @Override
    public User getSystemUser() {
        return Sender.system;
    }
}
