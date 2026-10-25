ALTER TABLE ai_models DROP CONSTRAINT IF EXISTS ai_models_provider_check;
ALTER TABLE ai_models
    ADD CONSTRAINT ai_models_provider_check CHECK (provider IN ('openrouter', 'gemini', 'groq', 'cloudflare'));

INSERT INTO ai_models (id, provider, model, display_name, enabled, sort_order)
VALUES ('cloudflare/meta/llama-3.3-70b-instruct-fp8-fast', 'cloudflare',
        '@cf/meta/llama-3.3-70b-instruct-fp8-fast', 'Cloudflare Llama 3.3 70B (FP8 Fast)', TRUE, 40)
ON CONFLICT (id) DO NOTHING;
