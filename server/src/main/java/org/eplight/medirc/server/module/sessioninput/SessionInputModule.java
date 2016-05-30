package org.eplight.medirc.server.module.sessioninput;

import com.google.protobuf.ByteString;
import org.eplight.medirc.protocol.*;
import org.eplight.medirc.server.event.EventLoop;
import org.eplight.medirc.server.event.consumers.FunctionConsumer;
import org.eplight.medirc.server.event.dispatchers.function.MessageDispatcher;
import org.eplight.medirc.server.event.dispatchers.function.message.AuthedMessageFunction;
import org.eplight.medirc.server.event.events.ChannelInactiveEvent;
import org.eplight.medirc.server.event.events.session.*;
import org.eplight.medirc.server.image.Image;
import org.eplight.medirc.server.image.ImageManager;
import org.eplight.medirc.server.image.repo.ImageRepository;
import org.eplight.medirc.server.image.repo.ImageRepositoryException;
import org.eplight.medirc.server.image.transformations.ImageFragment;
import org.eplight.medirc.server.image.transformations.ImageTransformations;
import org.eplight.medirc.server.image.transformations.RectImageFragment;
import org.eplight.medirc.server.module.Module;
import org.eplight.medirc.server.network.SocketAttributes;
import org.eplight.medirc.server.session.Session;
import org.eplight.medirc.server.session.SessionState;
import org.eplight.medirc.server.session.active.ActiveSessionsManager;
import org.eplight.medirc.server.user.ActiveUser;
import org.eplight.medirc.server.user.User;
import org.eplight.medirc.server.user.factory.UserRepository;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Created by EpLightning on 06.05.2016.
 */
public class SessionInputModule implements Module {

    @Inject
    private EventLoop loop;

    @Inject
    private MessageDispatcher dispatcher;

    @Inject
    private ActiveSessionsManager sessions;

    @Inject
    private ImageManager imageManager;

    @Inject
    private ImageRepository imageRepository;

    @Inject
    private UserRepository userRepository;

    private SessionResponses.GenericResponse statusSuccess(int session) {
        SessionResponses.GenericResponse.Builder b = SessionResponses.GenericResponse.newBuilder();
        b.setSuccess(true);
        b.setSessionId(session);

        return b.build();
    }

    private SessionResponses.GenericResponse statusError(int session, String error) {
        SessionResponses.GenericResponse.Builder b = SessionResponses.GenericResponse.newBuilder();
        b.setSuccess(false);
        b.setSessionId(session);
        b.setError(error);

        return b.build();
    }

    private void onChannelInactive(ChannelInactiveEvent event) {
        ActiveUser usr = event.getChannel().attr(SocketAttributes.USER_OBJECT).get();

        if (usr == null) {
            return;
        }

        Set<Session> related = sessions.findActiveForUser(usr);

        for (Session s : related) {
            loop.fireEvent(new PartSessionEvent(s, "Utracono połączenie z serwerem", usr));
        }
    }

    private void onJoinRequest(ActiveUser user, SessionRequests.JoinRequest msg) {
        SessionResponses.JoinResponse.Builder response = SessionResponses.JoinResponse.newBuilder();

        Session sess = sessions.findById(msg.getId());

        // not found
        if (sess == null) {
            response.setStatus(statusError(msg.getId(), "Nie udało się znaleźć sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // already active
        if (sess.getActiveUsers().contains(user)) {
            response.setStatus(statusError(msg.getId(), "Już jesteś zalogowany w podanej sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // permissions
        if (!sess.isAllowedToJoin(user)) {
            response.setStatus(statusError(msg.getId(), "Nie masz uprawnień do wejścia do danej sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        response.setStatus(statusSuccess(msg.getId()));
        response.setData(sess.buildDataMessage());
        response.setYourFlags(SessionUserFlag.toProtobuf(sess.getFlags(user)));

        sess.getActiveUsers().stream()
                .forEach(a -> response.addActiveUser(a.buildSessionUserMessage(sess.getFlags(a))));

        sess.getParticipants().stream()
                .forEach(a -> response.addParticipant(a.buildSessionUserMessage(sess.getFlags(a))));

        imageManager.getSessionImages(sess).stream()
                .forEach(a -> response.addImage(a.getId()));

        user.getChannel().writeAndFlush(response.build());

        loop.fireEvent(new JoinSessionEvent(sess, user, user));
    }

    private void onPartRequest(ActiveUser user, SessionRequests.PartRequest msg) {
        Session sess = sessions.findById(msg.getId());

        // not found
        if (sess == null) {
            return;
        }

        // not active
        if (!sess.getActiveUsers().contains(user)) {
            return;
        }

        loop.fireEvent(new PartSessionEvent(sess, user, "Klient opuścił sesję", user));
    }

    private void onChangeSettings(ActiveUser user, SessionRequests.ChangeSettings msg) {
        SessionResponses.ChangeSettingsResponse.Builder response = SessionResponses.ChangeSettingsResponse.newBuilder();

        Session sess = sessions.findById(msg.getSessionId());

        // not found
        if (sess == null) {
            response.setStatus(statusError(msg.getSessionId(), "Nie udało się znaleźć sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // ?
        if (msg.getData() == null) {
            response.setStatus(statusError(msg.getSessionId(), "Błąd klienta, brak danych"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // uprawnienia
        if (!sess.isAdmin(user)) {
            response.setStatus(statusError(msg.getSessionId(), "Nie masz uprawnień do zmiany ustawień"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // zakończona
        if (sess.getState() == SessionState.Finished) {
            response.setStatus(statusError(msg.getSessionId(), "Sesja jest już zakończona"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // check name
        if (msg.getData().getName().length() < 1) {
            response.setStatus(statusError(msg.getSessionId(), "Zła nazwa"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // wrong state changes
        Main.Session.State newState = msg.getData().getState();

        if ((newState == Main.Session.State.SettingUp && sess.getState() == SessionState.Started) ||
                (newState == Main.Session.State.Finished && sess.getState() == SessionState.SettingUp)) {
            response.setStatus(statusError(msg.getSessionId(), "Nieprawidłowa zmiana stanu sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        response.setStatus(statusSuccess(msg.getSessionId()));

        user.getChannel().writeAndFlush(response.build());

        loop.fireEvent(new SettingsSessionEvent(sess, user, msg.getData()));
    }

    private void onInviteUser(ActiveUser user, SessionRequests.InviteUser msg) {
        SessionResponses.InviteUserResponse.Builder response = SessionResponses.InviteUserResponse.newBuilder();

        Session sess = sessions.findById(msg.getSessionId());

        // not found
        if (sess == null) {
            response.setStatus(statusError(msg.getSessionId(), "Nie udało się znaleźć sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // uprawnienia
        if (!sess.isAdmin(user)) {
            response.setStatus(statusError(msg.getSessionId(), "Nie masz uprawnień do zmiany ustawień"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // zakończona
        if (sess.getState() == SessionState.Finished) {
            response.setStatus(statusError(msg.getSessionId(), "Sesja jest już zakończona"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        User foundUser = null;

        if (msg.getUserId() > 0) {
            foundUser = userRepository.findById(msg.getUserId());
        } else if (!msg.getUserName().isEmpty()) {
            foundUser = userRepository.findByName(msg.getUserName());
        }

        if (foundUser == null) {
            response.setStatus(statusError(msg.getSessionId(), "Nie znaleziono takiego użytkownika"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // już jest w sesji?
        if (sess.getOwner().equals(foundUser) || sess.getParticipants().contains(foundUser)) {
            response.setStatus(statusError(msg.getSessionId(), "Użytkownik jest już w sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        response.setStatus(statusSuccess(msg.getSessionId()));
        response.setUser(foundUser.buildSessionUserMessage(EnumSet.noneOf(SessionUserFlag.class)));

        user.getChannel().writeAndFlush(response.build());

        loop.fireEvent(new InviteSessionEvent(sess, user, foundUser));
    }

    private void onKickUser(ActiveUser user, SessionRequests.KickUser msg) {
        SessionResponses.KickUserResponse.Builder response = SessionResponses.KickUserResponse.newBuilder();

        Session sess = sessions.findById(msg.getSessionId());

        // not found
        if (sess == null) {
            response.setStatus(statusError(msg.getSessionId(), "Nie udało się znaleźć sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // uprawnienia
        if (!sess.isAdmin(user)) {
            response.setStatus(statusError(msg.getSessionId(), "Nie masz uprawnień do zmiany ustawień"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // zakończona
        if (sess.getState() == SessionState.Finished) {
            response.setStatus(statusError(msg.getSessionId(), "Sesja jest już zakończona"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        User foundUser = userRepository.findById(msg.getUserId());

        if (foundUser == null) {
            response.setStatus(statusError(msg.getSessionId(), "Nie znaleziono takiego użytkownika"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // can be kicked?
        if (sess.getOwner().equals(foundUser) || !sess.getParticipants().contains(foundUser)) {
            response.setStatus(statusError(msg.getSessionId(), "Nie można wyrzucić tego użytkownika"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        response.setStatus(statusSuccess(msg.getSessionId()));

        user.getChannel().writeAndFlush(response.build());

        loop.fireEvent(new KickSessionEvent(sess, user, foundUser));
    }

    private void onChangeUserFlags(ActiveUser user, SessionRequests.ChangeUserFlags msg) {
        SessionResponses.ChangeUserFlagsResponse.Builder response = SessionResponses.ChangeUserFlagsResponse.newBuilder();

        Session sess = sessions.findById(msg.getSessionId());

        // not found
        if (sess == null) {
            response.setStatus(statusError(msg.getSessionId(), "Nie udało się znaleźć sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // uprawnienia
        if (!sess.isAdmin(user)) {
            response.setStatus(statusError(msg.getSessionId(), "Nie masz uprawnień do zmiany ustawień"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // zakończona
        if (sess.getState() == SessionState.Finished) {
            response.setStatus(statusError(msg.getSessionId(), "Sesja jest już zakończona"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        User foundUser = userRepository.findById(msg.getUserId());

        if (foundUser == null) {
            response.setStatus(statusError(msg.getSessionId(), "Nie znaleziono takiego użytkownika"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        EnumSet<SessionUserFlag> flags = SessionUserFlag.fromProtobuf(msg.getFlags());

        // bez sens
        if ((flags.contains(SessionUserFlag.Owner) && sess.getOwner().equals(foundUser)) ||
                (!flags.contains(SessionUserFlag.Owner) && sess.getOwner().equals(foundUser))) {
            response.setStatus(statusError(msg.getSessionId(), "Flaga właściciela jest stała"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        response.setStatus(statusSuccess(msg.getSessionId()));

        user.getChannel().writeAndFlush(response.build());

        loop.fireEvent(new ChangeFlagsSessionEvent(sess, user, foundUser, flags));
    }

    private void onUploadImage(ActiveUser user, SessionRequests.UploadImage msg) {
        SessionResponses.UploadImageResponse.Builder response = SessionResponses.UploadImageResponse.newBuilder();

        Session sess = sessions.findById(msg.getSessionId());

        // not found
        if (sess == null) {
            response.setStatus(statusError(msg.getSessionId(), "Nie udało się znaleźć sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // uprawnienia
        if (!sess.isAdmin(user)) {
            response.setStatus(statusError(msg.getSessionId(), "Nie masz uprawnień do zmiany ustawień"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // zakończona
        if (sess.getState() == SessionState.Finished) {
            response.setStatus(statusError(msg.getSessionId(), "Sesja jest już zakończona"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        if (msg.getName().isEmpty() || msg.getData().isEmpty()) {
            response.setStatus(statusError(msg.getSessionId(), "Brak danych"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        Image img = imageRepository.create(sess.getId());

        try {
            img.importImage(msg.getData().toByteArray());
            img.setName(msg.getName());

            imageRepository.persist(img);
            imageManager.addImage(img);
        } catch (IOException e) {
            response.setStatus(statusError(msg.getSessionId(), "Nieprawidłowy format obrazka"));
            user.getChannel().writeAndFlush(response.build());
            return;
        } catch (ImageRepositoryException e) {
            response.setStatus(statusError(msg.getSessionId(), "Błąd wewnętrzny serwera"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        response.setStatus(statusSuccess(sess.getId())).setId(img.getId());

        user.getChannel().writeAndFlush(response.build());

        loop.fireEvent(new UploadImageSessionEvent(sess, user, img));
    }

    private void onRemoveImage(ActiveUser user, SessionRequests.RemoveImage msg) {
        SessionResponses.RemoveImageResponse.Builder response = SessionResponses.RemoveImageResponse.newBuilder();

        if (msg.getId() <= 0)
            return;

        Image img = imageManager.getImage(msg.getId());

        if (img == null)
            return;

        Session sess = sessions.findById(img.getSessionId());

        // not found
        if (sess == null) {
            response.setStatus(statusError(img.getSessionId(), "Nie udało się znaleźć sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // uprawnienia
        if (!sess.isAdmin(user)) {
            response.setStatus(statusError(img.getSessionId(), "Nie masz uprawnień do zmiany ustawień"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // zakończona
        if (sess.getState() == SessionState.Finished) {
            response.setStatus(statusError(img.getSessionId(), "Sesja jest już zakończona"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        imageManager.removeImage(img);
        imageRepository.remove(img);

        response.setStatus(statusSuccess(sess.getId()));

        user.getChannel().writeAndFlush(response.build());

        loop.fireEvent(new RemoveImageSessionEvent(sess, user, img));
    }

    private void onSendMessage(ActiveUser user, SessionRequests.SendMessage msg) {
        Session sess = sessions.findById(msg.getSessionId());

        // not found
        if (sess == null)
            return;

        // not active
        if (!sess.getActiveUsers().contains(user))
            return;

        // empty?
        if (msg.getText().isEmpty())
            return;

        loop.fireEvent(new MessageSessionEvent(sess, user, user, msg.getText()));
    }

    private void onRequestImage(ActiveUser user, SessionRequests.RequestImage msg) {
        SessionResponses.RequestImageResponse.Builder response = SessionResponses.RequestImageResponse.newBuilder();

        if (msg.getId() <= 0)
            return;

        Image img = imageManager.getImage(msg.getId());

        if (img == null)
            return;

        Session sess = sessions.findById(img.getSessionId());

        // not found
        if (sess == null) {
            response.setStatus(statusError(img.getSessionId(), "Nie udało się znaleźć sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // active?
        if (!sess.getActiveUsers().contains(user)) {
            response.setStatus(statusError(img.getSessionId(), "Musisz być aktywnym użytkownikiem sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        response.setId(img.getId())
                .setData(ByteString.copyFrom(img.getData()))
                .setName(img.getName())
                .setColorG(img.getColor().getG())
                .setColorB(img.getColor().getB())
                .setColorR(img.getColor().getR())
                .setTransformations(img.getTransformations().toProtobuf())
                .setStatus(statusSuccess(sess.getId()));

        img.getFragments().forEach(f -> response.addFragment(f.toProtobuf()));

        user.getChannel().writeAndFlush(response.build());
    }

    private void onTransformImage(ActiveUser user, SessionRequests.TransformImage msg) {
        SessionResponses.TransformImageResponse.Builder response = SessionResponses.TransformImageResponse.newBuilder();

        if (msg.getId() <= 0)
            return;

        Image img = imageManager.getImage(msg.getId());

        if (img == null)
            return;

        Session sess = sessions.findById(img.getSessionId());

        // not found
        if (sess == null) {
            response.setStatus(statusError(img.getSessionId(), "Nie udało się znaleźć sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // active?
        if (!sess.getActiveUsers().contains(user)) {
            response.setStatus(statusError(img.getSessionId(), "Musisz być aktywnym użytkownikiem sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // zoom prawidłowy
        double resultSize = msg.getTransformations().getZoom() * Math.max(img.getWidth(), img.getHeight());

        if (resultSize <= 1 || resultSize > 4096 || msg.getTransformations().getZoom() < 0.1) {
            response.setStatus(statusError(img.getSessionId(), "Nieprawidłowy zoom"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // TODO: Uprawnienia głosu itd.

        ImageTransformations f = new ImageTransformations(msg.getTransformations());

        img.setTransformations(f);

        response.setStatus(statusSuccess(sess.getId()));
        user.getChannel().writeAndFlush(response.build());

        loop.fireEvent(new TransformImageSessionEvent(sess, user, img));
    }

    private void onAddImageFragment(ActiveUser user, SessionRequests.AddImageFragment msg) {
        SessionResponses.AddImageFragmentResponse.Builder response = SessionResponses.AddImageFragmentResponse
                .newBuilder();

        if (msg.getId() <= 0)
            return;

        Image img = imageManager.getImage(msg.getId());

        if (img == null)
            return;

        Session sess = sessions.findById(img.getSessionId());

        // not found
        if (sess == null) {
            response.setStatus(statusError(img.getSessionId(), "Nie udało się znaleźć sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // active?
        if (!sess.getActiveUsers().contains(user)) {
            response.setStatus(statusError(img.getSessionId(), "Musisz być aktywnym użytkownikiem sesji"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        // TODO: Uprawnienia głosu itd.

        // TODO: Pare fragmentów naraz
        img.getFragments().clear();

        // id
        Optional<ImageFragment> lastFragment = img.getFragments().stream().max(Comparator.comparingInt(ImageFragment::getId));
        int fragmentId = 1;

        if (lastFragment.isPresent())
            fragmentId = lastFragment.get().getId() + 1;

        // TODO: Jakaś fabryka
        if (msg.getFragment().getFragCase() != SessionBasic.ImageFragment.FragCase.RECT) {
            response.setStatus(statusError(img.getSessionId(), "Nieprawidłowy typ fragmentu"));
            user.getChannel().writeAndFlush(response.build());
            return;
        }

        RectImageFragment frag = new RectImageFragment(fragmentId);
        frag.fromProtobuf(msg.getFragment().getRect());

        img.getFragments().add(frag);

        response.setStatus(statusSuccess(sess.getId()));
        user.getChannel().writeAndFlush(response.build());

        loop.fireEvent(new AddImageFragmentSessionEvent(sess, user, img, frag));
    }

    @Override
    public void start() {
        loop.registerConsumer(new FunctionConsumer<>(ChannelInactiveEvent.class, this::onChannelInactive));

        dispatcher.register(SessionRequests.JoinRequest.class, new AuthedMessageFunction<>(this::onJoinRequest));
        dispatcher.register(SessionRequests.PartRequest.class, new AuthedMessageFunction<>(this::onPartRequest));
        dispatcher.register(SessionRequests.ChangeSettings.class, new AuthedMessageFunction<>(this::onChangeSettings));
        dispatcher.register(SessionRequests.InviteUser.class, new AuthedMessageFunction<>(this::onInviteUser));
        dispatcher.register(SessionRequests.KickUser.class, new AuthedMessageFunction<>(this::onKickUser));
        dispatcher.register(SessionRequests.ChangeUserFlags.class, new AuthedMessageFunction<>(this::onChangeUserFlags));
        dispatcher.register(SessionRequests.UploadImage.class, new AuthedMessageFunction<>(this::onUploadImage));
        dispatcher.register(SessionRequests.RemoveImage.class, new AuthedMessageFunction<>(this::onRemoveImage));
        dispatcher.register(SessionRequests.SendMessage.class, new AuthedMessageFunction<>(this::onSendMessage));
        dispatcher.register(SessionRequests.RequestImage.class, new AuthedMessageFunction<>(this::onRequestImage));
        dispatcher.register(SessionRequests.TransformImage.class, new AuthedMessageFunction<>(this::onTransformImage));
        dispatcher.register(SessionRequests.AddImageFragment.class, new AuthedMessageFunction<>(this::onAddImageFragment));
    }

    @Override
    public void stop() {

    }
}
