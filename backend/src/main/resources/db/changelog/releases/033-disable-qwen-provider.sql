--liquibase formatted sql

--changeset any2api:033-001-disable-qwen-provider
UPDATE providers SET enabled = FALSE WHERE id = 'qwen';
UPDATE registration_schedules SET enabled = FALSE WHERE provider_id = 'qwen';
UPDATE registration_jobs SET status = 'CANCELLED', finished_at = CURRENT_TIMESTAMP,
    last_error_class = 'PROVIDER_DISABLED', last_error_detail = 'Provider qwen is temporarily disabled'
WHERE provider_id = 'qwen' AND status IN ('PENDING', 'RUNNING');
UPDATE registration_schedules
SET job_command = jsonb_set(job_command, '{attemptTimeoutSeconds}', '600'::jsonb)
WHERE COALESCE((job_command->>'attemptTimeoutSeconds')::int, 0) < 600;

--rollback UPDATE providers SET enabled = TRUE WHERE id = 'qwen'; UPDATE registration_schedules SET enabled = TRUE WHERE provider_id = 'qwen';
