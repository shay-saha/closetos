ALTER TABLE reembedding_item DROP CONSTRAINT reembedding_item_event_id_fkey;
ALTER TABLE reembedding_item ADD CONSTRAINT reembedding_item_event_id_fkey
    FOREIGN KEY (event_id) REFERENCES outbox_event(id) ON DELETE CASCADE;

ALTER TABLE outbox_event ADD COLUMN wardrobe_id uuid;

UPDATE outbox_event o SET wardrobe_id = i.wardrobe_id
FROM garment_image i WHERE o.aggregate_type = 'image' AND o.aggregate_id = i.id;

UPDATE outbox_event o SET wardrobe_id = g.wardrobe_id
FROM garment g WHERE o.wardrobe_id IS NULL AND o.aggregate_type = 'garment' AND o.aggregate_id = g.id;

UPDATE outbox_event o SET wardrobe_id = w.id
FROM wardrobe w WHERE o.wardrobe_id IS NULL AND o.payload->>'wardrobeId' = w.id::text;

-- Deletion events must retain their owner even after the image or garment row has gone.
UPDATE outbox_event o SET wardrobe_id = w.id
FROM wardrobe w
WHERE o.wardrobe_id IS NULL AND EXISTS (
    SELECT 1 FROM jsonb_each_text(o.payload) part
    WHERE part.key IN ('prefix', 'sourceKey')
        AND split_part(part.value, '/', 1) = 'users'
        AND split_part(part.value, '/', 2) = w.owner_id::text
);

-- Work with no remaining target cannot produce a result. Unassigned pending deletion
-- events are retained: the NOT NULL constraint refuses migration rather than losing cleanup.
DELETE FROM outbox_event WHERE wardrobe_id IS NULL
    AND (published_at IS NOT NULL OR event_type IN ('START_PROCESSING', 'GENERATE_EMBEDDING', 'REBUILD_EMBEDDING'));

ALTER TABLE outbox_event ALTER COLUMN wardrobe_id SET NOT NULL;
ALTER TABLE outbox_event ADD CONSTRAINT outbox_event_wardrobe_id_fkey
    FOREIGN KEY (wardrobe_id) REFERENCES wardrobe(id) ON DELETE CASCADE;
CREATE INDEX outbox_event_wardrobe ON outbox_event (wardrobe_id);

DELETE FROM reembedding_job j WHERE j.wardrobe_id IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM wardrobe w WHERE w.id = j.wardrobe_id);
ALTER TABLE reembedding_job ADD CONSTRAINT reembedding_job_wardrobe_id_fkey
    FOREIGN KEY (wardrobe_id) REFERENCES wardrobe(id) ON DELETE CASCADE;
