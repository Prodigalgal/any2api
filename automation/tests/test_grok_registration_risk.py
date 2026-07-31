from any2api_automation.providers.grok_protocol.registration_risk import (
    GrokRegistrationRisk,
    inspect_registration_risk_page,
    parse_registration_risk,
)


def test_registration_rsc_denial_is_parsed_without_persisting_raw_details():
    risk = parse_registration_risk(
        r"{\"botFlagSource\":1,\"botFlagDetails\":"
        r"\"policy=deny,risk=0.98,event=$registration\"}"
    )

    assert risk.status == "denied"
    assert risk.denied is True
    assert risk.metadata() == {
        "registration_risk_status": "denied",
        "bot_flag_source": 1,
        "registration_risk_policy": "deny",
        "registration_risk_score": 0.98,
        "registration_risk_event": "$registration",
    }
    assert "bot_flag_details" not in risk.metadata()


def test_source_zero_is_clean_but_missing_fields_remain_unknown():
    assert (
        parse_registration_risk(r"{\"botFlagSource\":0,\"botFlagDetails\":null}").status == "clean"
    )
    assert parse_registration_risk("<html></html>").status == "unknown"


def test_source_one_without_registration_deny_is_flagged_not_clean():
    risk = parse_registration_risk(
        r"{\"botFlagSource\":1,\"botFlagDetails\":"
        r"\"policy=review,risk=0.7,event=$registration\"}"
    )

    assert risk.status == "flagged"
    assert risk.denied is False


def test_page_probe_failure_is_unknown_and_does_not_leak_error_text():
    class BrokenPage:
        def goto(self, *_args, **_kwargs):
            raise RuntimeError("secret cookie value")

    assert inspect_registration_risk_page(BrokenPage()) == GrokRegistrationRisk("unknown")
