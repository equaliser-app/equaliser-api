package events.equaliser.java.model.event;

import events.equaliser.java.model.image.Image;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.ext.sql.SQLConnection;

import java.util.List;

/**
 * A series without fixtures.
 */
public class BareSeries {

    private final int id;

    private final List<Tag> tags;

    private final List<Image> images;

    public int getId() {
        return id;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public List<Image> getImages() {
        return images;
    }

    public BareSeries(int id, List<Tag> tags, List<Image> images) {
        this.id = id;
        this.tags = tags;
        this.images = images;
    }

    public static void retrieveFromId(int id,
                                      SQLConnection connection,
                                      Handler<AsyncResult<? extends BareSeries>> result) {
        // retrieve tags
        Tag.retrieveFromSeries(id, connection, seriesTags -> {
                    if (seriesTags.succeeded()) {
                        List<Tag> tags = seriesTags.result();

                        // retrieve images
                        Image.retrieveFromSeries(id, connection, seriesImages -> {
                            if (seriesImages.succeeded()) {
                                List<Image> images = seriesImages.result();
                                result.handle(Future.succeededFuture(new BareSeries(id, tags, images)));
                            }
                            else {
                                result.handle(Future.failedFuture(seriesImages.cause()));
                            }
                        });
                    }
                    else {
                        result.handle(Future.failedFuture(seriesTags.cause()));
                    }
                });
    }
}
