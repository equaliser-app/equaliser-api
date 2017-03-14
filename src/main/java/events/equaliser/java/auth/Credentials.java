package events.equaliser.java.auth;

import events.equaliser.java.model.user.User;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import org.mindrot.jbcrypt.BCrypt;


public class Credentials {

    private static final int BCRYPT_ROUNDS = 10;

    public static String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_ROUNDS));
    }

    public static void validate(String username_email,
                                String password,
                                SQLConnection connection,
                                Handler<AsyncResult<User>> handler) {
        JsonArray params = new JsonArray()
                .add(username_email)
                .add(username_email);
        System.out.println("Retrieving user from credentials");
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
                    "Users.Password AS UserPassword, " +
                    "Users.ImageID AS UserImageID, " +
                    "Countries.CountryID, " +
                    "Countries.Name AS CountryName, " +
                    "Countries.Abbreviation AS CountryAbbreviation, " +
                    "Countries.CallingCode AS CountryCallingCode " +
                "FROM Users " +
                    "INNER JOIN Countries " +
                        "ON Countries.CountryID = Users.CountryID " +
                "WHERE Users.Username = ? OR Users.Email = ?;",
                params, credentialsResult -> {
                    System.out.println("User retrieve success?: " + credentialsResult.succeeded());
                    if (credentialsResult.succeeded()) {
                        ResultSet results = credentialsResult.result();
                        if (results.getNumRows() == 0) {
                            // user not found
                            handler.handle(Future.failedFuture("Invalid credentials"));
                        }
                        else {
                            JsonObject row = results.getRows().get(0);
                            String hashed = row.getString("UserPassword");
                            if (BCrypt.checkpw(password, hashed)) {
                                User user = User.fromJsonObject(row);
                                handler.handle(Future.succeededFuture(user));
                            }
                            else {
                                handler.handle(Future.failedFuture("Invalid credentials"));
                            }
                        }
                    }
                    else {
                        handler.handle(Future.failedFuture(credentialsResult.cause()));
                    }
                });
    }
}
