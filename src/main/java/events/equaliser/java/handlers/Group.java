package events.equaliser.java.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import events.equaliser.java.auth.Session;
import events.equaliser.java.model.event.Tier;
import events.equaliser.java.model.group.PaymentGroup;
import events.equaliser.java.model.ticket.Offer;
import events.equaliser.java.model.ticket.Transaction;
import events.equaliser.java.model.user.PublicUser;
import events.equaliser.java.model.user.User;
import events.equaliser.java.util.Json;
import events.equaliser.java.util.Request;
import events.equaliser.java.verticles.PrimaryPoolVerticle;
import io.vertx.core.*;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sync.Sync;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Int;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;


public class Group {

    private static final Logger logger = LoggerFactory.getLogger(Group.class);

    public static void postCreate(RoutingContext context,
                                  SQLConnection connection,
                                  Handler<AsyncResult<JsonNode>> handler) {
        HttpServerRequest request = context.request();
        MultiMap attributes = request.formAttributes();
        String tierIdRaw = request.getFormAttribute("tierId");
        if (tierIdRaw == null) {
            Request.missingParam("tierId", handler);
            return;
        }
        try {
            int tierId = Integer.parseInt(tierIdRaw);
            List<String> attendees = attributes.getAll("attendees").stream()
                    .filter(member -> !member.isEmpty())
                    .collect(Collectors.toList());
            if (attendees.isEmpty()) {
                handler.handle(Future.failedFuture("List of group members is empty"));
                return;
            }
            logger.debug("Attendees: {}", attendees);

            List<String> guests = attributes.getAll("guests");
            if (!guests.stream().allMatch(attendees::contains)) {
                handler.handle(Future.failedFuture("The list of guests must be a subset of the list of attendees"));
                return;
            }
            logger.debug("Guests: {}", guests);



            Tier.retrieveById(tierId, connection, tierRes -> {
                if (tierRes.failed()) {
                    handler.handle(Future.failedFuture("Tier not recognised"));
                    return;
                }

                Tier tier = tierRes.result();
                logger.debug("Tier: {}", tier);
                User.retrieveFromUsernames(attendees, connection, usersRes -> {
                    if (usersRes.failed()) {
                        handler.handle(Future.failedFuture(usersRes.cause()));
                        return;
                    }

                    Map<String, User> users = usersRes.result();
                    logger.debug("Users: {}", users);

                    // ensure none of the attendees are already in a group for the event
                    String attendeeUserIds = users.values().stream()
                            .map(User::getId)
                            .map(str -> '\'' + String.valueOf(str) + '\'')
                            .collect(Collectors.joining(","));
                    JsonArray params = new JsonArray()
                            .add(tier.getFixture().getId())
                            .add(tier.getFixture().getId());
                    connection.queryWithParams(String.format(
                            "SELECT 1 " +
                            "FROM Fixtures " +
                                "INNER JOIN Tiers " +
                                    "ON Tiers.FixtureID = Fixtures.FixtureID " +
                                "INNER JOIN Offers " +
                                    "ON Offers.TierID = Tiers.TierID " +
                                "INNER JOIN Groups " +
                                    "ON Groups.GroupID = Offers.GroupID " +
                                "INNER JOIN PaymentGroups " +
                                    "ON PaymentGroups.GroupID = Groups.GroupID " +
                                "INNER JOIN PaymentGroupAttendees " +
                                    "ON PaymentGroupAttendees.PaymentGroupID = PaymentGroups.PaymentGroupID " +
                            "WHERE PaymentGroupAttendees.UserID IN (%s) " +
                            "AND Fixtures.FixtureID = ? " +
                            "UNION " +
                            "SELECT 1 " +
                            "FROM Fixtures " +
                                "INNER JOIN Tiers " +
                                    "ON Tiers.FixtureID = Fixtures.FixtureID " +
                                "INNER JOIN GroupTiers " +
                                    "ON GroupTiers.TierID = Tiers.TierID " +
                                "INNER JOIN Groups " +
                                    "ON Groups.GroupID = GroupTiers.GroupID " +
                                "INNER JOIN PaymentGroups " +
                                    "ON PaymentGroups.GroupID = Groups.GroupID " +
                                "INNER JOIN PaymentGroupAttendees " +
                                    "ON PaymentGroupAttendees.PaymentGroupID = PaymentGroups.PaymentGroupID " +
                            "WHERE PaymentGroupAttendees.UserID IN (%s)" +
                            "AND Fixtures.FixtureID = ?", attendeeUserIds, attendeeUserIds),
                            params, existingRes -> {
                                if (existingRes.failed()) {
                                    handler.handle(Future.failedFuture(existingRes.cause()));
                                    return;
                                }

                                if (existingRes.result().getNumRows() != 0) {
                                    handler.handle(Future.failedFuture(
                                            "One or more group members are already waiting to see this event"));
                                    return;
                                }

                                Session session = context.get("session");
                                User leader = session.getUser();
                                Map<User, Set<User>> paymentGroups = new HashMap<>();
                                Set<User> guestUsers = guests.stream()
                                        .map(users::get)
                                        .collect(Collectors.toSet());
                                Set<User> attendeeUsers = attendees.stream()
                                        .map(users::get)
                                        .filter(user -> !guestUsers.contains(user)) // remove guests
                                        .collect(Collectors.toSet());
                                if (attendeeUsers.contains(leader)) {
                                    // add to guests so will attend - as also payee, will pay for self
                                    guestUsers.add(leader);

                                    // remove from additional list as these will pay for themselves
                                    attendeeUsers.remove(leader);
                                }
                                paymentGroups.put(leader, guestUsers);

                                for (User attendee : attendeeUsers) {
                                    paymentGroups.put(attendee, new HashSet<>(Collections.singleton(attendee)));
                                }

                                logger.debug("Payment groups: {}", paymentGroups);
                                logger.debug("Identified {} attendees, {} guests and {} payment groups",
                                        paymentGroups.entrySet().stream()
                                                .map(Map.Entry::getValue)
                                                .mapToInt(Set::size)
                                                .sum(),
                                        paymentGroups.get(leader).contains(leader) ?
                                                paymentGroups.get(leader).size() - 1 :
                                                paymentGroups.get(leader).size(),
                                        paymentGroups.size());

                                events.equaliser.java.model.group.Group.create(leader, connection, groupRes -> {
                                    if (groupRes.failed()) {
                                        handler.handle(Future.failedFuture("Failed to create a new group"));
                                        return;
                                    }

                                    events.equaliser.java.model.group.Group bare = groupRes.result();
                                    PaymentGroup.create(bare, paymentGroups, connection, groupsRes -> {
                                        if (groupsRes.failed()) {
                                            handler.handle(Future.failedFuture(groupsRes.cause()));
                                            return;
                                        }

                                        events.equaliser.java.model.group.Group group = groupsRes.result();

                                        EventBus eb = Vertx.currentContext().owner().eventBus();
                                        eb.send(PrimaryPoolVerticle.PRIMARY_POOL_RESERVE_ADDRESS,
                                                new JsonObject()
                                                        .put("tierId", tier.getId())
                                                        .put("count", group.getSize()),
                                                reserveRes -> {
                                            if (reserveRes.failed()) {
                                                // could also just use the waiting list...
                                                handler.handle(Future.failedFuture(reserveRes.cause()));
                                                return;
                                            }

                                            JsonObject reply = (JsonObject)reserveRes.result().body();

                                            if (!reply.getBoolean("success")) {
                                                // tickets unavailable; client should ask for additional tiers
                                                Map<Integer, Integer> ranks = new HashMap<>();
                                                ranks.put(tier.getId(), 1);
                                                group.insertAdditionalTiers(ranks, connection, rankRes -> {
                                                    if (rankRes.failed()) {
                                                        handler.handle(Future.failedFuture(rankRes.cause()));
                                                        return;
                                                    }

                                                    ObjectNode wrapper = Json.FACTORY.objectNode();
                                                    wrapper.set("tier", Json.MAPPER.convertValue(tier, JsonNode.class));
                                                    handler.handle(Future.succeededFuture(wrapper));
                                                });
                                            }
                                            else {
                                                // tickets reserved; create offer
                                                Offer.create(group, tier, connection, Sync.fiberHandler(offerRes -> {
                                                    if (offerRes.failed()) {
                                                        handler.handle(Future.failedFuture(offerRes.cause()));
                                                        return;
                                                    }

                                                    Offer offer = offerRes.result();
                                                    offer.sendNotificationsSync(connection);

                                                    // client should proceed to payment
                                                    ObjectNode wrapper = Json.FACTORY.objectNode();
                                                    wrapper.set("offer", Json.MAPPER.convertValue(offer, JsonNode.class));
                                                    handler.handle(Future.succeededFuture(wrapper));
                                                }));
                                            }
                                        });
                                    });
                                });
                            });
                });
            });
        } catch (NumberFormatException e) {
            handler.handle(Future.failedFuture("tierId must be numeric"));
        }
    }

    private static void extractGroup(HttpServerRequest request,
                                     BiFunction<HttpServerRequest, String, String> extractor, // request, field -> value
                                     Handler<AsyncResult<Integer>> handler) {
        String groupIdStr = extractor.apply(request, "id");
        try {
            Request.validateField("id", groupIdStr);
            Integer groupId = Integer.parseInt(groupIdStr);
            handler.handle(Future.succeededFuture(groupId));
        } catch (NumberFormatException e) {
            handler.handle(Future.failedFuture("Non-numeric groupId field"));
        } catch (IllegalArgumentException e) {
            handler.handle(Future.failedFuture(e.getMessage()));
        }
    }

    public static void postTiers(RoutingContext context,
                                 SQLConnection connection,
                                 Handler<AsyncResult<JsonNode>> handler) {
        HttpServerRequest request = context.request();
        MultiMap fields = request.formAttributes();
        try {
            String groupIdStr = request.getParam("id");
            Request.validateField("id", groupIdStr);
            int groupId = Integer.parseInt(groupIdStr);

            Map<Integer, Integer> ranks = new HashMap<>();
            for (Map.Entry<String, String> field : fields) {
                // assume a tierId -> rank map
                ranks.put(
                        Integer.parseInt(field.getKey()),
                        Integer.parseInt(field.getValue()));
            }

            events.equaliser.java.model.group.Group.retrieveById(groupId, connection, groupRes -> {
                if (groupRes.failed()) {
                    handler.handle(Future.failedFuture(groupRes.cause()));
                    return;
                }

                events.equaliser.java.model.group.Group group = groupRes.result();

                Session session = context.get("session");
                User user = session.getUser();
                if (!group.getLeader().equals(user)) {
                    handler.handle(Future.failedFuture("Only the group leader can set the group's tiers"));
                    return;
                }

                group.insertAdditionalTiers(ranks, connection, ranksRes -> {
                    if (ranksRes.failed()) {
                        handler.handle(Future.failedFuture(ranksRes.cause()));
                        return;
                    }

                    handler.handle(Future.succeededFuture());
                });
            });
        } catch (NumberFormatException e) {
            handler.handle(Future.failedFuture("Non-numeric value"));
        } catch (IllegalArgumentException e) {
            handler.handle(Future.failedFuture(e));
        }
    }

    public static void postPay(RoutingContext context,
                               SQLConnection connection,
                               Handler<AsyncResult<JsonNode>> handler) {
        HttpServerRequest request = context.request();
        extractGroup(request, Request::getParam, groupIdRes -> {
            if (groupIdRes.failed()) {
                handler.handle(Future.failedFuture(groupIdRes.cause()));
                return;
            }

            int groupId = groupIdRes.result();
            events.equaliser.java.model.group.Group.retrieveById(groupId, connection, groupRes -> {
                if (groupRes.failed()) {
                    handler.handle(Future.failedFuture(groupRes.cause()));
                    return;
                }

                events.equaliser.java.model.group.Group group = groupRes.result();
                logger.debug("Retrieved group {}", group);
                Session session = context.get("session");
                User user = session.getUser();
                Optional<PaymentGroup> paymentGroup = group.getPaymentGroup(user);
                if (!paymentGroup.isPresent()) {
                    handler.handle(Future.failedFuture("You are not a payee in this group"));
                    return;
                }

                logger.debug("Payment group attendees: {}", paymentGroup.get().getAttendees());

                Offer.retrieveByGroup(group, connection, offerRes -> {
                    if (offerRes.failed()) {
                        handler.handle(Future.failedFuture(offerRes.cause()));
                        return;
                    }

                    Optional<Offer> offerOptional = offerRes.result();
                    if (!offerOptional.isPresent()) {
                        handler.handle(Future.failedFuture("No offer has been made to the group"));
                        return;
                    }

                    Offer offer = offerOptional.get();
                    logger.debug("Retrieved offer {}", offer);
                    if (offer.getExpires().isBefore(OffsetDateTime.now())) {
                        handler.handle(Future.failedFuture("Offer has expired"));
                        return;
                    }

                    Transaction.create(paymentGroup.get(), offer, connection, transactionRes -> {
                        if (transactionRes.failed()) {
                            handler.handle(Future.failedFuture(transactionRes.cause()));
                            return;
                        }

                        Transaction transaction = transactionRes.result();
                        ObjectNode wrapper = Json.FACTORY.objectNode();
                        wrapper.set("transaction", Json.MAPPER.convertValue(transaction, JsonNode.class));
                        handler.handle(Future.succeededFuture(wrapper));
                    });
                });
            });
        });
    }

    public static void getId(RoutingContext context,
                             SQLConnection connection,
                             Handler<AsyncResult<JsonNode>> handler) {
        HttpServerRequest request = context.request();
        extractGroup(request, Request::getParam, groupIdRes -> {
            if (groupIdRes.failed()) {
                handler.handle(Future.failedFuture(groupIdRes.cause()));
                return;
            }

            int groupId = groupIdRes.result();
            events.equaliser.java.model.group.Group.retrieveById(groupId, connection, groupRes -> {
                if (groupRes.failed()) {
                    handler.handle(Future.failedFuture(groupRes.cause()));
                    return;
                }

                events.equaliser.java.model.group.Group group = groupRes.result();
                Session session = context.get("session");
                User user = session.getUser();
                if (!group.getLeader().equals(user)) {
                    handler.handle(Future.failedFuture("Only the group leader can view the group"));
                    return;
                }
                JsonNode node = Json.MAPPER.convertValue(group, JsonNode.class);
                handler.handle(Future.succeededFuture(node));
            });
        });
    }
}
