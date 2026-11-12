-- Route SMS rows inserted by the Android app straight into the order flow,
-- replacing the /device/sms webhook. Safe to run more than once.
CREATE OR REPLACE FUNCTION public.own_sim_sms_route()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_otp      text := NEW.otp_code;
  v_ids      uuid[];
  v_order    uuid;
  v_flipped  uuid;
  v_next     public.own_sim_queue%ROWTYPE;
  v_prov     record;
  v_sim      text;
BEGIN
  IF v_otp IS NULL OR btrim(v_otp) = '' OR v_otp ~ '^0+$' THEN
    RETURN NEW;
  END IF;

  SELECT array_agg(id) INTO v_ids
  FROM public.api_providers WHERE kind = 'custom' AND enabled = true;
  IF v_ids IS NULL THEN RETURN NEW; END IF;

  SELECT id INTO v_order FROM public.otp_orders
  WHERE provider_id = ANY(v_ids) AND status = 'pending'
  ORDER BY created_at DESC LIMIT 1
  FOR UPDATE SKIP LOCKED;
  IF v_order IS NULL THEN RETURN NEW; END IF;

  UPDATE public.otp_orders
  SET status = 'received', otp_code = v_otp, completed_at = now()
  WHERE id = v_order AND status = 'pending'
  RETURNING id INTO v_flipped;
  IF v_flipped IS NULL THEN RETURN NEW; END IF;

  -- promote next queued user
  SELECT * INTO v_next FROM public.own_sim_queue ORDER BY created_at ASC LIMIT 1;
  IF v_next.id IS NULL THEN RETURN NEW; END IF;

  SELECT id, name, api_key INTO v_prov FROM public.api_providers WHERE id = v_ids[1];
  v_sim := regexp_replace(btrim(coalesce(v_prov.api_key, '')), '^\+', '');
  IF v_sim !~ '^\d{8,15}$' THEN RETURN NEW; END IF;

  INSERT INTO public.otp_orders
    (user_id, activation_id, provider, provider_id, service_code, service_name,
     country_code, country_name, phone_number, base_cost, charged_amount, status, expires_at)
  VALUES
    (v_next.user_id, gen_random_uuid()::text, coalesce(v_prov.name, 'Fresh Num'), v_prov.id,
     v_next.service_code, v_next.service_name, '22', 'India', v_sim, 0,
     v_next.charged_amount, 'pending', now() + interval '20 minutes');
  DELETE FROM public.own_sim_queue WHERE id = v_next.id;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS own_sim_sms_route_trg ON public.own_sim_sms;
CREATE TRIGGER own_sim_sms_route_trg
AFTER INSERT ON public.own_sim_sms
FOR EACH ROW EXECUTE FUNCTION public.own_sim_sms_route();

NOTIFY pgrst, 'reload schema';
