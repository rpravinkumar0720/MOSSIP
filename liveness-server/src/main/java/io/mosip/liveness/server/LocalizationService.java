package io.mosip.liveness.server;

import io.mosip.liveness.api.challenge.ChallengeType;
import io.mosip.liveness.api.decision.DecisionType;
import io.mosip.liveness.api.error.LivenessError;
import io.mosip.liveness.api.quality.QualityFailureReason;
import io.mosip.liveness.core.engine.DefaultLivenessEngine.EngineState;

import java.util.HashMap;
import java.util.Map;

/**
 * Provides localized user prompts and guidance messages in English and Tamil (ta).
 */
public class LocalizationService {

    private static final Map<String, String> EN = new HashMap<>();
    private static final Map<String, String> TA = new HashMap<>();

    static {
        // Quality guidance (English)
        EN.put(QualityFailureReason.NO_FACE_DETECTED.name(), QualityFailureReason.NO_FACE_DETECTED.getGuidanceMessage());
        EN.put(QualityFailureReason.MULTIPLE_FACES_DETECTED.name(), QualityFailureReason.MULTIPLE_FACES_DETECTED.getGuidanceMessage());
        EN.put(QualityFailureReason.FACE_TOO_SMALL.name(), QualityFailureReason.FACE_TOO_SMALL.getGuidanceMessage());
        EN.put(QualityFailureReason.FACE_TOO_CLOSE.name(), QualityFailureReason.FACE_TOO_CLOSE.getGuidanceMessage());
        EN.put(QualityFailureReason.POSE_YAW_EXCEEDED.name(), QualityFailureReason.POSE_YAW_EXCEEDED.getGuidanceMessage());
        EN.put(QualityFailureReason.POSE_PITCH_EXCEEDED.name(), QualityFailureReason.POSE_PITCH_EXCEEDED.getGuidanceMessage());
        EN.put(QualityFailureReason.POSE_ROLL_EXCEEDED.name(), QualityFailureReason.POSE_ROLL_EXCEEDED.getGuidanceMessage());
        EN.put(QualityFailureReason.TOO_DARK.name(), QualityFailureReason.TOO_DARK.getGuidanceMessage());
        EN.put(QualityFailureReason.TOO_BRIGHT.name(), QualityFailureReason.TOO_BRIGHT.getGuidanceMessage());
        EN.put(QualityFailureReason.BLURRY.name(), QualityFailureReason.BLURRY.getGuidanceMessage());
        EN.put(QualityFailureReason.EYES_CLOSED.name(), QualityFailureReason.EYES_CLOSED.getGuidanceMessage());
        EN.put(QualityFailureReason.MOUTH_OPEN.name(), QualityFailureReason.MOUTH_OPEN.getGuidanceMessage());

        // Quality guidance (Tamil)
        TA.put(QualityFailureReason.NO_FACE_DETECTED.name(), "முகம் கண்டறியப்படவில்லை. சட்டத்திற்குள் முகத்தை வைக்கவும்.");
        TA.put(QualityFailureReason.MULTIPLE_FACES_DETECTED.name(), "பல முகங்கள் கண்டறியப்பட்டன. ஒருவர் மட்டுமே இருப்பதை உறுதிசெய்யவும்.");
        TA.put(QualityFailureReason.FACE_TOO_SMALL.name(), "முகம் மிக தூரத்தில் உள்ளது. கேமராவுக்கு அருகில் வரவும்.");
        TA.put(QualityFailureReason.FACE_TOO_CLOSE.name(), "முகம் மிக அருகில் உள்ளது. சற்று பின்வாங்கவும்.");
        TA.put(QualityFailureReason.POSE_YAW_EXCEEDED.name(), "முகம் திரும்பியுள்ளது. கேமராவை நேராகப் பார்க்கவும்.");
        TA.put(QualityFailureReason.POSE_PITCH_EXCEEDED.name(), "தலை சாய்ந்துள்ளது. தலையை நேராக வைக்கவும்.");
        TA.put(QualityFailureReason.POSE_ROLL_EXCEEDED.name(), "தலை பக்கவாட்டில் சாய்ந்துள்ளது. சமநிலையில் வைக்கவும்.");
        TA.put(QualityFailureReason.TOO_DARK.name(), "வெளிச்சம் போதவில்லை. வெளிச்சமான பகுதிக்கு செல்லவும்.");
        TA.put(QualityFailureReason.TOO_BRIGHT.name(), "அதிக வெளிச்சம் உள்ளது. முகத்தில் விழும் ஒளியைக் குறைக்கவும்.");
        TA.put(QualityFailureReason.BLURRY.name(), "படம் தெளிவாக இல்லை. அசையாமல் இருக்கவும்.");
        TA.put(QualityFailureReason.EYES_CLOSED.name(), "கண்கள் மூடியுள்ளன. கண்களைத் திறந்து வைக்கவும்.");
        TA.put(QualityFailureReason.MOUTH_OPEN.name(), "வாய் திறந்துள்ளது. வாயை மூடி வைக்கவும்.");

        // States
        EN.put("STATE_" + EngineState.FACE_POSITIONING.name(), "Position your face in the oval");
        EN.put("STATE_" + EngineState.PASSIVE_CHECK.name(), "Hold still, analyzing face liveness...");
        EN.put("STATE_" + EngineState.ACTIVE_CHALLENGE.name(), "Perform the requested action");
        EN.put("STATE_" + EngineState.DECIDED.name(), "Verification completed");

        TA.put("STATE_" + EngineState.FACE_POSITIONING.name(), "வட்டத்திற்குள் உங்கள் முகத்தை வைக்கவும்");
        TA.put("STATE_" + EngineState.PASSIVE_CHECK.name(), "அசையாமல் இருக்கவும், சரிபார்க்கப்படுகிறது...");
        TA.put("STATE_" + EngineState.ACTIVE_CHALLENGE.name(), "கேட்கப்பட்ட செயலைச் செய்யவும்");
        TA.put("STATE_" + EngineState.DECIDED.name(), "சரிபார்ப்பு முடிந்தது");

        // Challenges
        EN.put(ChallengeType.BLINK.name(), "Please blink your eyes naturally");
        EN.put(ChallengeType.SMILE.name(), "Please smile");
        EN.put(ChallengeType.TURN_LEFT.name(), "Turn your head slowly to your left");
        EN.put(ChallengeType.TURN_RIGHT.name(), "Turn your head slowly to your right");
        EN.put(ChallengeType.LOOK_UP.name(), "Tilt your head up slightly");
        EN.put(ChallengeType.NOD_HEAD.name(), "Nod your head up and down");

        TA.put(ChallengeType.BLINK.name(), "இயல்பாக கண்களை இமைக்கவும்");
        TA.put(ChallengeType.SMILE.name(), "புன்னகைக்கவும்");
        TA.put(ChallengeType.TURN_LEFT.name(), "தலையை மெதுவாக இடதுபுறம் திருப்பவும்");
        TA.put(ChallengeType.TURN_RIGHT.name(), "தலையை மெதுவாக வலதுபுறம் திருப்பவும்");
        TA.put(ChallengeType.LOOK_UP.name(), "தலையை சற்று மேலே உயர்த்தவும்");
        TA.put(ChallengeType.NOD_HEAD.name(), "தலையை மேலும் கீழும் அசைக்கவும்");

        // Decisions / Errors
        EN.put("DECISION_" + DecisionType.ACCEPTED.name(), "Face verification successful.");
        EN.put("DECISION_" + DecisionType.REJECTED.name(), LivenessError.PAD_FAILURE.getDefaultUserMessage());
        EN.put("DECISION_" + DecisionType.RETRY.name(), "Please adjust your position and try again.");
        EN.put("DECISION_" + DecisionType.FAILED_MAX_RETRIES.name(), LivenessError.MAX_RETRIES_EXCEEDED.getDefaultUserMessage());
        EN.put("DECISION_ERROR", LivenessError.ENGINE_ERROR.getDefaultUserMessage());

        TA.put("DECISION_" + DecisionType.ACCEPTED.name(), "முக சரிபார்ப்பு வெற்றிகரமாக முடிந்தது.");
        TA.put("DECISION_" + DecisionType.REJECTED.name(), "முக சரிபார்ப்பை நிறைவு செய்ய முடியவில்லை. மீண்டும் முயற்சிக்கவும்.");
        TA.put("DECISION_" + DecisionType.RETRY.name(), "மீண்டும் முயற்சிக்கவும்.");
        TA.put("DECISION_" + DecisionType.FAILED_MAX_RETRIES.name(), "அதிகபட்ச முயற்சிகள் முடிவடைந்தது. மேற்பார்வையாளரைத் தொடர்பு கொள்ளவும்.");
        TA.put("DECISION_ERROR", "கணினி பிழை ஏற்பட்டது. மீண்டும் முயற்சிக்கவும்.");
    }

    public static String getQualityGuidance(QualityFailureReason reason, String lang) {
        if (reason == null) return "";
        return translate(reason.name(), reason.getGuidanceMessage(), lang);
    }

    public static String getStatePrompt(EngineState state, String lang) {
        if (state == null) return "";
        return translate("STATE_" + state.name(), "Look at the camera", lang);
    }

    public static String getChallengePrompt(ChallengeType type, String lang) {
        if (type == null) return "";
        return translate(type.name(), "Perform the requested action", lang);
    }

    public static String getDecisionMessage(DecisionType type, String lang) {
        if (type == null) return "";
        String fallback = switch (type) {
            case ACCEPTED -> "Face verification successful.";
            case REJECTED -> LivenessError.PAD_FAILURE.getDefaultUserMessage();
            case RETRY -> "Please adjust your position and try again.";
            case FAILED_MAX_RETRIES -> LivenessError.MAX_RETRIES_EXCEEDED.getDefaultUserMessage();
            case CHALLENGE_REQUIRED -> "Action required";
            case ERROR -> LivenessError.ENGINE_ERROR.getDefaultUserMessage();
        };
        return translate("DECISION_" + type.name(), fallback, lang);
    }

    public static String translate(String key, String fallback, String lang) {
        if (lang != null && lang.trim().toLowerCase().startsWith("ta")) {
            String val = TA.get(key);
            if (val != null) return val;
        }
        String enVal = EN.get(key);
        return enVal != null ? enVal : fallback;
    }
}
