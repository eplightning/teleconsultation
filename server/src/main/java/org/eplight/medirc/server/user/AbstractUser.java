package org.eplight.medirc.server.user;

import org.eplight.medirc.protocol.Main;
import org.eplight.medirc.protocol.SessionBasic;
import org.eplight.medirc.protocol.SessionUserFlag;

import java.util.EnumSet;

public class AbstractUser implements User {

    protected int id;
    protected String name;

    public AbstractUser(int id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public Main.User buildUserMessage() {
        return Main.User.newBuilder()
                .setId(getId())
                .setName(getName())
                .build();
    }

    @Override
    public SessionBasic.SessionUser buildSessionUserMessage(EnumSet<SessionUserFlag> flags) {
        return SessionBasic.SessionUser.newBuilder()
                .setId(getId())
                .setName(getName())
                .setFlags(SessionUserFlag.toProtobuf(flags))
                .build();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof User)) return false;

        User user = (User) o;

        return id == user.getId();
    }

    @Override
    public int hashCode() {
        return id;
    }
}
