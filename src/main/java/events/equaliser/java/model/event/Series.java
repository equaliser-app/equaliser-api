package events.equaliser.java.model.event;

import events.equaliser.java.model.image.Image;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.ext.sql.SQLConnection;

import java.util.List;

public class Series extends BareSeries {

    private final List<Fixture> fixtures;

    public List<Fixture> getFixtures() {
        return fixtures;
    }

    private Series(int id, List<Tag> tags, List<Image> images, List<Fixture> fixtures) {
        super(id, tags, images);
        this.fixtures = fixtures;
    }

    private static Series withFixtures(BareSeries series, List<Fixture> fixtures) {
        return new Series(series.getId(), series.getTags(), series.getImages(), fixtures);
    }

    public static void retrieveFromId(int id,
                                      SQLConnection connection,
                                      Handler<AsyncResult<? extends BareSeries>> result) {
        BareSeries.retrieveFromId(id, connection, bareSeriesResult -> {
                    if (bareSeriesResult.succeeded()) {
                        BareSeries series = bareSeriesResult.result();
                        // retrieve fixtures
                        Fixture.retrieveFromSeries(series, connection, seriesFixtures -> {
                            if (seriesFixtures.succeeded()) {
                                List<Fixture> fixtures = seriesFixtures.result();
                                result.handle(Future.succeededFuture(Series.withFixtures(series, fixtures)));
                            }
                            else {
                                result.handle(Future.failedFuture(seriesFixtures.cause()));
                            }
                        });
                    }
                    else {
                        result.handle(Future.failedFuture(bareSeriesResult.cause()));
                    }
                });
    }
}
