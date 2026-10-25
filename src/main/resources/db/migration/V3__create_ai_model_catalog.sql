CREATE TABLE ai_models (
    id VARCHAR(240) PRIMARY KEY,
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('openrouter', 'gemini')),
    model VARCHAR(200) NOT NULL,
    display_name VARCHAR(240) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 100,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_models_provider_model UNIQUE (provider, model)
);

INSERT INTO ai_models (id, provider, model, display_name, enabled, sort_order) VALUES
    ('openrouter/openrouter/free', 'openrouter', 'openrouter/free', 'OpenRouter Free', TRUE, 10),
    ('gemini/gemini-flash-latest', 'gemini', 'gemini-flash-latest', 'Gemini Flash Latest', TRUE, 20)
ON CONFLICT (id) DO NOTHING;
