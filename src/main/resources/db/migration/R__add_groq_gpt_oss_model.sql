ALTER TABLE ai_models DROP CONSTRAINT IF EXISTS ai_models_provider_check;
ALTER TABLE ai_models
    ADD CONSTRAINT ai_models_provider_check CHECK (provider IN ('openrouter', 'gemini', 'groq', 'cloudflare'));

INSERT INTO ai_models (id, provider, model, display_name, enabled, sort_order)
VALUES ('groq/openai/gpt-oss-20b', 'groq', 'openai/gpt-oss-20b', 'GPT OSS 20B (Groq)', TRUE, 30)
ON CONFLICT (id) DO NOTHING;
