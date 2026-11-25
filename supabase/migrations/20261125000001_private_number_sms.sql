-- ============================================================================
-- Private Number SMS Inbox
--
-- private_number_assignments  — admin assigns a rented phone number to a user
-- private_number_sms          — every SMS received on that number, tagged to
--                               that user. Written exclusively by the Android
--                               OTP Relay app via service_role key.
--
-- Flow:
--   1. Admin creates a row in private_number_assignments for a user.
--   2. Admin copies the user's UUID and gives it to them alongside the
--      assigned phone number.
--   3. User enters their UUID + enables "Private Mode" in the Android app.
--   4. App writes incoming SMS to private_number_sms (not own_sim_sms).
--   5. User sees a live private inbox on the website (Realtime subscription).
--   6. Admin can view all users' private SMS in the admin panel.
-- ============================================================================

-- ── private_number_assignments ───────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.private_number_assignments (
  id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at   timestamptz  NOT NULL DEFAULT now(),
  user_id      uuid         NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  phone_number text         NOT NULL DEFAULT '',
  label        text         NOT NULL DEFAULT '',
  enabled      boolean      NOT NULL DEFAULT true
);

-- One assignment per user (admin reassigns by updating the row)
CREATE UNIQUE INDEX IF NOT EXISTS pna_user_idx
  ON public.private_number_assignments (user_id);

CREATE INDEX IF NOT EXISTS pna_enabled_idx
  ON public.private_number_assignments (enabled);

ALTER TABLE public.private_number_assignments ENABLE ROW LEVEL SECURITY;

-- Users: read their own assignment (needed to know if the inbox tab should show)
CREATE POLICY "user reads own assignment" ON public.private_number_assignments
  FOR SELECT TO authenticated
  USING (auth.uid() = user_id);

-- Admins: full CRUD
CREATE POLICY "admin manages assignments" ON public.private_number_assignments
  FOR ALL TO authenticated
  USING (
    EXISTS (SELECT 1 FROM public.user_roles WHERE user_id = auth.uid() AND role = 'admin')
  )
  WITH CHECK (
    EXISTS (SELECT 1 FROM public.user_roles WHERE user_id = auth.uid() AND role = 'admin')
  );

-- Service role: unrestricted (server-side upserts from admin functions)
CREATE POLICY "service role manages assignments" ON public.private_number_assignments
  FOR ALL TO service_role USING (true) WITH CHECK (true);

-- ── private_number_sms ───────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.private_number_sms (
  id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at   timestamptz  NOT NULL DEFAULT now(),
  user_id      uuid         NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  phone_number text,                   -- the SIM's own number (from app config)
  sender       text,                   -- originating address of the SMS
  message      text         NOT NULL DEFAULT '',
  otp_code     text                    -- extracted 4-8 digit code, if any
);

-- Primary access pattern: user reads own rows ordered newest-first
CREATE INDEX IF NOT EXISTS pns_user_created_idx
  ON public.private_number_sms (user_id, created_at DESC);

-- Admin access: all rows newest-first
CREATE INDEX IF NOT EXISTS pns_created_idx
  ON public.private_number_sms (created_at DESC);

ALTER TABLE public.private_number_sms ENABLE ROW LEVEL SECURITY;

-- Users: read own rows only (private inbox)
CREATE POLICY "user reads own private sms" ON public.private_number_sms
  FOR SELECT TO authenticated
  USING (auth.uid() = user_id);

-- Admins: read all rows
CREATE POLICY "admin reads all private sms" ON public.private_number_sms
  FOR SELECT TO authenticated
  USING (
    EXISTS (SELECT 1 FROM public.user_roles WHERE user_id = auth.uid() AND role = 'admin')
  );

-- Service role: insert only (Android app uses service_role key)
CREATE POLICY "service role inserts private sms" ON public.private_number_sms
  FOR INSERT TO service_role WITH CHECK (true);

-- ── Realtime ─────────────────────────────────────────────────────────────────
-- Both tables need Realtime so the web UI can subscribe without polling.

ALTER PUBLICATION supabase_realtime ADD TABLE public.private_number_assignments;
ALTER PUBLICATION supabase_realtime ADD TABLE public.private_number_sms;

NOTIFY pgrst, 'reload schema';
