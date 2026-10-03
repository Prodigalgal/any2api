import base64
import copy

import pytest

from any2api_automation.providers.longcat_browser import (
    _longcat_upload_sources,
    build_longcat_request,
)


@pytest.mark.parametrize(
    "extension,mime,nested",
    [("txt", "text/plain", False), ("TXT", "application/octet-stream", True)],
)
def test_text_document_content_is_available_without_changing_uploaded_bytes(
    extension, mime, nested
):
    text = "真实附件正文\nReference token: maple_913872\n# Document"
    source = f"data:{mime};base64," + base64.b64encode(text.encode()).decode()
    filename = "fixture." + extension
    command = {
        "schemaVersion": 1,
        "model": "longcat-flash",
        "generation": {},
        "reasoning": {},
        "providerOptions": {},
        "controls": {},
        "tools": [],
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": "Read the reference token from the attached document.",
                    },
                    {
                        "type": "input_file",
                        "input_file": {"filename": filename, "file_data": source},
                    }
                    if nested
                    else {"type": "input_file", "filename": filename, "file_data": source},
                ],
            }
        ],
    }
    original = copy.deepcopy(command)
    uploaded = [
        {
            "fileName": filename,
            "fileUrl": "https://upload.longcat.chat/file",
            "fileKey": "fixture-key",
        }
    ]
    prepared = build_longcat_request(command, uploaded_files=uploaded)
    assert text in prepared["content"]
    assert source not in prepared["content"]
    assert _longcat_upload_sources(command["messages"])[0]["dataUrl"] == source
    assert prepared["files"] == uploaded
    assert command == original


def test_invalid_utf8_text_document_is_rejected_explicitly():
    command = {
        "schemaVersion": 1,
        "model": "longcat-flash",
        "generation": {},
        "reasoning": {},
        "providerOptions": {},
        "controls": {},
        "tools": [],
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_file",
                        "filename": "fixture.txt",
                        "file_data": "data:text/plain;base64,/w==",
                    },
                ],
            }
        ],
    }
    with pytest.raises(ValueError, match="UTF-8"):
        build_longcat_request(
            command,
            uploaded_files=[
                {
                    "fileName": "fixture.txt",
                    "fileUrl": "https://upload.longcat.chat/file",
                    "fileKey": "fixture-key",
                }
            ],
        )
