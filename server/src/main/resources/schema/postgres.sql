-- iris read-model schema. Idempotent: every CREATE is IF NOT EXISTS, applied in
-- one multi-statement execute at boot (SchemaMigrator). The vault is the source
-- of truth — this entire schema is disposable and rebuildable by a full rescan.

CREATE TABLE IF NOT EXISTS notes (
  path              TEXT PRIMARY KEY,      -- vault-relative, '/', NFC-normalized
  folder            TEXT NOT NULL,
  name              TEXT NOT NULL,         -- basename sans .md
  content_hash      CHAR(64) NOT NULL,     -- sha256 hex of raw bytes
  size_bytes        BIGINT NOT NULL,
  modified_at       TIMESTAMPTZ NOT NULL,  -- file mtime
  frontmatter       JSONB,                 -- parsed; NULL if absent or broken
  frontmatter_raw   TEXT,                  -- exact text between the fences
  frontmatter_error TEXT,
  body              TEXT NOT NULL,         -- sans frontmatter
  indexed_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS notes_folder_idx ON notes (folder);
CREATE INDEX IF NOT EXISTS notes_name_ci_idx ON notes (lower(name));

CREATE TABLE IF NOT EXISTS note_tags (
  path   TEXT NOT NULL REFERENCES notes(path) ON DELETE CASCADE,
  tag    TEXT NOT NULL,                    -- normalized (lowercase), no '#'
  raw    TEXT NOT NULL,                    -- as written
  source TEXT NOT NULL,                    -- 'frontmatter' | 'inline'
  PRIMARY KEY (path, tag, source)
);
CREATE INDEX IF NOT EXISTS note_tags_tag_idx ON note_tags (tag);

CREATE TABLE IF NOT EXISTS note_links (
  source_path   TEXT NOT NULL REFERENCES notes(path) ON DELETE CASCADE,
  ordinal       INT  NOT NULL,             -- position within the note
  raw_target    TEXT NOT NULL,
  header        TEXT,
  alias         TEXT,
  embed         BOOLEAN NOT NULL DEFAULT FALSE,
  resolved_path TEXT,                      -- no FK: the target may not exist (yet)
  PRIMARY KEY (source_path, ordinal)
);
CREATE INDEX IF NOT EXISTS note_links_resolved_idx ON note_links (resolved_path);
CREATE INDEX IF NOT EXISTS note_links_target_ci_idx ON note_links (lower(raw_target));

CREATE TABLE IF NOT EXISTS scans (
  id          BIGSERIAL PRIMARY KEY,
  kind        TEXT NOT NULL,               -- 'full' | 'watch'
  started_at  TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ,
  notes_seen  INT,
  created     INT,
  changed     INT,
  deleted     INT,
  errors      INT
);
