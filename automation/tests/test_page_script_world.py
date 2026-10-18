from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from patchright.async_api import Page as PatchrightPage

from any2api_automation.providers.official_browser import evaluate_in_page_world


@pytest.mark.asyncio
@pytest.mark.parametrize("patchright", [False, True])
@pytest.mark.parametrize("has_argument", [False, True])
async def test_page_owned_scripts_select_the_main_world_only_for_patchright(
    patchright, has_argument
):
    evaluate = AsyncMock(return_value="page-owned-sdk")
    page = (
        Mock(spec=PatchrightPage, evaluate=evaluate)
        if patchright
        else SimpleNamespace(evaluate=evaluate)
    )
    arguments = ({"requestId": "one-request"},) if has_argument else ()
    assert await evaluate_in_page_world(page, "page-owned-script", *arguments) == "page-owned-sdk"
    options = {"isolated_context": False} if patchright else {}
    evaluate.assert_awaited_once_with("page-owned-script", *arguments, **options)


@pytest.mark.asyncio
async def test_script_signature_errors_do_not_replay_a_request():
    evaluate = AsyncMock(side_effect=TypeError("provider script failed after a side effect"))
    page = Mock(spec=PatchrightPage, evaluate=evaluate)
    with pytest.raises(TypeError, match="side effect"):
        await evaluate_in_page_world(page, "send-once", None)
    evaluate.assert_awaited_once_with("send-once", None, isolated_context=False)
