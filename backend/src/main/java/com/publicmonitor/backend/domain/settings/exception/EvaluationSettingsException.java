package com.publicmonitor.backend.domain.settings.exception;
import com.publicmonitor.backend.global.exception.BaseException;
public class EvaluationSettingsException extends BaseException {
    public EvaluationSettingsException(EvaluationSettingsResponseCode code) { super(code); }
}
