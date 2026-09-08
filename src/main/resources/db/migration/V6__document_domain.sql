ALTER TABLE document ADD COLUMN domain VARCHAR(16) NOT NULL DEFAULT 'skb';

CREATE INDEX idx_document_domain ON document(domain);
