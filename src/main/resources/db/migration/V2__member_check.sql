ALTER TABLE form_session ADD COLUMN kind TEXT NOT NULL DEFAULT 'JOIN';
ALTER TABLE submission ADD COLUMN kind TEXT NOT NULL DEFAULT 'JOIN';
CREATE TABLE recheck (chat_id BIGINT PRIMARY KEY REFERENCES group_chat(chat_id) ON DELETE CASCADE,
  deadline TIMESTAMP WITH TIME ZONE NOT NULL, started_by BIGINT NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL, closed_at TIMESTAMP WITH TIME ZONE);
CREATE TABLE recheck_message (chat_id BIGINT NOT NULL REFERENCES recheck(chat_id) ON DELETE CASCADE,
  message_id BIGINT NOT NULL, PRIMARY KEY (chat_id, message_id));
CREATE TABLE recheck_notice (chat_id BIGINT NOT NULL REFERENCES recheck(chat_id) ON DELETE CASCADE,
  admin_id BIGINT NOT NULL, delivered BOOLEAN NOT NULL, PRIMARY KEY (chat_id, admin_id));
CREATE TABLE member (chat_id BIGINT NOT NULL REFERENCES group_chat(chat_id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL, passed_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY (chat_id, user_id));
INSERT INTO member (chat_id, user_id, passed_at)
  SELECT chat_id, user_id, MAX(decided_at) FROM submission WHERE status = 'APPROVED' GROUP BY chat_id, user_id;
