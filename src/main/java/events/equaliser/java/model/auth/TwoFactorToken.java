package events.equaliser.java.model.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import com.twilio.rest.api.v2010.account.MessageCreator;
import events.equaliser.java.model.user.User;
import events.equaliser.java.util.Hex;
import events.equaliser.java.util.Random;
import events.equaliser.java.util.Time;
import io.vertx.core.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;

public class TwoFactorToken {

    private static final Logger logger = LoggerFactory.getLogger(TwoFactorToken.class);

    private static final int CODE_LENGTH = 6;
    private static final int TOKEN_LENGTH = 32;
    private static final int TOKEN_VALIDITY_MINUTES = 10;

    private final User user;
    private final byte[] token;
    private final String code;
    private final String sid;
    private final OffsetDateTime expires;

    @JsonIgnore
    public User getUser() {
        return user;
    }

    @JsonIgnore
    public byte[] getToken() {
        return token;
    }

    @JsonIgnore
    public String getCode() {
        return code;
    }

    @JsonIgnore
    public String getSid() {
        return sid;
    }

    @JsonProperty("token")
    public String getTokenHex() {
        return Hex.binToHex(getToken());
    }

    public OffsetDateTime getExpires() {
        return expires;
    }

    public TwoFactorToken(User user, byte[] token, String code, String sid, OffsetDateTime expires) {
        this.user = user;
        this.token = token;
        this.code = code;
        this.sid = sid;
        this.expires = expires;
    }

    public static void initiate(User user,
                                SQLConnection connection,
                                Handler<AsyncResult<TwoFactorToken>> handler) {
        Context vertx = Vertx.currentContext();
        String code = Random.getNumericString(CODE_LENGTH);
        MessageCreator creator = getMessage(user, code, vertx.config());
        vertx.executeBlocking(future -> {
            Message message = creator.create(); // no one seems to use createAsync()
            String sid = message.getSid();
            future.complete(sid);
        }, result -> {
            if (result.succeeded()) {
                String sid = (String)result.result();
                byte[] token = Random.getBytes(TOKEN_LENGTH);
                OffsetDateTime expires = OffsetDateTime.now().plusMinutes(TOKEN_VALIDITY_MINUTES);
                JsonArray params = new JsonArray()
                        .add(user.getId())
                        .add(token)
                        .add(code)
                        .add(sid)
                        .add(Time.toSql(expires));
                connection.updateWithParams(
                        "INSERT INTO TwoFactorTokens (UserID, Token, Code, Sid, Expires) " +
                        "VALUES (?, FROM_BASE64(?), ?, ?, ?);",
                        params, res -> {
                            if (res.succeeded()) {
                                TwoFactorToken inserted = new TwoFactorToken(user, token, code, sid, expires);
                                handler.handle(Future.succeededFuture(inserted));
                            }
                            else {
                                handler.handle(Future.failedFuture(res.cause()));
                            }
                        });
            }
            else {
                handler.handle(Future.failedFuture(result.cause()));
            }
        });
    }

    private static MessageCreator getMessage(User user, String code, JsonObject config) {
        JsonObject twilio = config.getJsonObject("twilio");
        String fromNumber = twilio.getJsonObject("number").getString("number");
        final PhoneNumber from = new PhoneNumber(fromNumber);
        final PhoneNumber to = new PhoneNumber(user.getPhoneNumber());
        final String message = String.format(
                "Hi %s, %s is your Equaliser verification code.",
                user.getForename(), code);
        logger.debug("Message(from: {}, to: {}, message: {})", from, to, message);
        return Message.creator(to, from, message);
    }

    public static void validate(byte[] token,
                                String code,
                                SQLConnection connection,
                                Handler<AsyncResult<User>> handler) {
        JsonArray params = new JsonArray().add(token).add(code);
        connection.queryWithParams(
                "SELECT " +
                    "Users.UserID, " +
                    "Users.Username AS UserUsername, " +
                    "Users.Forename AS UserForename, " +
                    "Users.Surname AS UserSurname, " +
                    "Users.Email AS UserEmail, " +
                    "Users.AreaCode AS UserAreaCode, " +
                    "Users.SubscriberNumber AS UserSubscriberNumber, " +
                    "Users.Token AS UserToken, " +
                    "Users.ImageID AS UserImageID, " +
                    "Countries.CountryID, " +
                    "Countries.Name AS CountryName, " +
                    "Countries.Abbreviation AS CountryAbbreviation, " +
                    "Countries.CallingCode AS CountryCallingCode " +
                "FROM TwoFactorTokens " +
                    "INNER JOIN Users " +
                        "ON Users.UserID = TwoFactorTokens.UserID " +
                    "INNER JOIN Countries " +
                        "ON Countries.CountryID = Users.CountryID " +
                "WHERE TwoFactorTokens.Token = FROM_BASE64(?) AND TwoFactorTokens.Code = ?;", params, validateRes -> {
                    if (validateRes.failed()) {
                        handler.handle(Future.failedFuture(validateRes.cause()));
                        return;
                    }

                    ResultSet result = validateRes.result();
                    if (result.getNumRows() != 1) {
                        handler.handle(Future.failedFuture("Invalid 2FA token"));
                        return;
                    }

                    JsonObject row = result.getRows().get(0);
                    User.retrieveProfilePicture(row, connection, handler);
                });
    }
}
