-- Profile pictures without photos (friends.md §1.1): an emoji on a colour, or a
-- "profile song" whose cover is the picture. JSON, validated by the API.
ALTER TABLE accounts ADD COLUMN avatar TEXT;
