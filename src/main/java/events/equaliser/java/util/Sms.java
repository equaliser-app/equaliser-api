package events.equaliser.java.util;

import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.type.PhoneNumber;
import events.equaliser.java.model.user.User;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Sms {

    private static final Logger logger = LoggerFactory.getLogger(Sms.class);

    private static final PhoneNumber FROM_SENDER_ID = new PhoneNumber(
            Vertx.currentContext().config()
                    .getJsonObject("twilio")
                    .getJsonObject("number")
                    .getString("friendly"));

    public static void send(String body, User recipient, Handler<AsyncResult<Message>> handler) {
        final PhoneNumber to = new PhoneNumber(recipient.getPhoneNumber());
        logger.debug("Sending message from {} to {} with content '{}'", FROM_SENDER_ID, to, body);
        send(Message.creator(new PhoneNumber(recipient.getPhoneNumber()), FROM_SENDER_ID, body), handler);
    }

    private static void send(MessageCreator creator,
                             Handler<AsyncResult<Message>> handler) {
        // TODO find a way to use createAsync(), although no one seems to use it, and it's not clear how it would integrate with Vert.x
        Vertx.currentContext().executeBlocking(result -> result.complete(creator.create()), handler);
    }
}
