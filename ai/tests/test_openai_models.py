import asyncio
import json
import subprocess
import sys
from pathlib import Path

import httpx
import pytest
from langchain.agents import create_agent
from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.tools import tool
from openai import APITimeoutError
from pydantic import BaseModel

from app.core.openai_models import ChatOpenAI, OpenAIEmbeddings


class Answer(BaseModel):
    answer: str


def completion(
    content='{"answer":"ok"}', *, finish='stop', refusal=None, calls=None, response_id='chat-test',
):
    return httpx.Response(200, headers={
        'x-request-id': 'req-test', 'openai-processing-ms': '12', 'private-header': 'SECRET',
    }, json={
        'id': response_id, 'object': 'chat.completion', 'created': 0, 'model': 'gpt-5-mini',
        'choices': [{'index': 0, 'finish_reason': finish, 'message': {
            'role': 'assistant', 'content': content, 'refusal': refusal, 'tool_calls': calls,
        }}],
        'usage': {'prompt_tokens': 10, 'completion_tokens': 20, 'total_tokens': 30,
                  'completion_tokens_details': {'reasoning_tokens': 5}},
    })


class Capture(BaseCallbackHandler):
    def __init__(self):
        self.responses = []

    def on_llm_end(self, response, **kwargs):
        self.responses.append(response)


def test_structured_transport_options_callbacks_and_safe_metadata():
    async def scenario():
        requests = []
        capture = Capture()

        def handler(request):
            requests.append(json.loads(request.content))
            return completion()

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            model = ChatOpenAI(
                model='gpt-5-mini', api_key='PRIVATE_KEY', timeout=3, max_retries=0,
                reasoning_effort='minimal', max_tokens=1200, include_response_headers=True,
                http_async_client=client,
            )
            result = await model.with_structured_output(Answer, include_raw=True).ainvoke(
                [('system', 'Return JSON'), ('human', 'Hello')], config={'callbacks': [capture]},
            )
            assert not client.is_closed
            assert 'PRIVATE_KEY' not in repr(model)
            assert 'api_key' not in model.model_dump()
        assert result['parsed'] == Answer(answer='ok') and result['parsing_error'] is None
        payload = requests[0]
        assert payload['max_completion_tokens'] == 1200 and 'max_tokens' not in payload
        assert payload['reasoning_effort'] == 'minimal'
        assert payload['messages'][1] == {'role': 'user', 'content': 'Hello'}
        schema = payload['response_format']['json_schema']
        assert schema['strict'] is True
        assert schema['schema']['required'] == ['answer']
        assert schema['schema']['additionalProperties'] is False
        assert len(capture.responses) == 1
        raw = result['raw']
        assert raw.usage_metadata['total_tokens'] == 30
        details = raw.response_metadata['token_usage']['completion_tokens_details']
        assert details['reasoning_tokens'] == 5
        assert raw.response_metadata['headers'] == {
            'x-request-id': 'req-test', 'openai-processing-ms': '12',
        }
        assert 'SECRET' not in str(capture.responses)

    asyncio.run(scenario())


@pytest.mark.parametrize('content,finish,refusal', [
    ('{', 'stop', None), ('{}', 'stop', None),
    ('{"answer":"partial"}', 'length', None),
    ('{"answer":"blocked"}', 'content_filter', None),
    (None, 'stop', 'refused'),
])
@pytest.mark.parametrize('include_raw', [True, False])
def test_invalid_structured_outputs_fail_closed(content, finish, refusal, include_raw):
    async def scenario():
        transport = httpx.MockTransport(lambda request: completion(
            content, finish=finish, refusal=refusal,
        ))
        async with httpx.AsyncClient(transport=transport) as client:
            model = ChatOpenAI(model='gpt-5-mini', api_key='test', http_async_client=client)
            runnable = model.with_structured_output(Answer, include_raw=include_raw)
            if include_raw:
                result = await runnable.ainvoke('test')
                assert result['parsed'] is None
                assert isinstance(result['parsing_error'], ValueError)
                assert result['raw'].response_metadata['finish_reason'] == finish
            else:
                with pytest.raises(ValueError):
                    await runnable.ainvoke('test')

    asyncio.run(scenario())


def test_real_langchain_agent_executes_tool_and_sends_result_back():
    @tool
    def read_notice() -> str:
        """Read the current notice."""
        return 'notice evidence'

    async def scenario():
        requests = []

        def handler(request):
            payload = json.loads(request.content)
            requests.append(payload)
            if len(requests) == 1:
                assert payload['tools'][0]['function']['name'] == 'read_notice'
                return completion(None, finish='tool_calls', calls=[{
                    'id': 'call-1', 'type': 'function',
                    'function': {'name': 'read_notice', 'arguments': '{}'},
                }])
            assert payload['messages'][-1]['role'] == 'tool'
            assert payload['messages'][-1]['tool_call_id'] == 'call-1'
            assert payload['messages'][-1]['content'] == 'notice evidence'
            return completion('done', response_id='chat-done')

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            model = ChatOpenAI(model='gpt-5-mini', api_key='test', http_async_client=client)
            agent = create_agent(model=model, tools=[read_notice])
            result = await agent.ainvoke({'messages': [('user', 'Read notice')]})
        assert result['messages'][-1].content == 'done'
        assert len(requests) == 2

    asyncio.run(scenario())


def test_timeout_uses_sdk_exception_without_hidden_retries():
    async def scenario():
        requests = []

        def handler(request):
            requests.append(request)
            raise httpx.ReadTimeout('test timeout', request=request)

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            model = ChatOpenAI(model='gpt-5-mini', api_key='test', max_retries=0,
                               timeout=7, http_async_client=client)
            with pytest.raises(APITimeoutError):
                await model.with_structured_output(Answer, include_raw=True).ainvoke('test')
        assert len(requests) == 1
        assert requests[0].extensions['timeout']['read'] == 7

    asyncio.run(scenario())


def test_cancellation_is_not_swallowed_by_structured_parser():
    async def scenario():
        started = asyncio.Event()

        async def handler(request):
            started.set()
            await asyncio.Event().wait()

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            model = ChatOpenAI(model='gpt-5-mini', api_key='test', http_async_client=client)
            task = asyncio.create_task(model.with_structured_output(
                Answer, include_raw=True,
            ).ainvoke('test'))
            await asyncio.wait_for(started.wait(), timeout=3)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task

    asyncio.run(scenario())


def test_embedding_batches_plain_text_and_restores_input_order():
    async def scenario():
        requests = []
        texts = [f'공고 {index}' for index in range(35)]

        def handler(request):
            payload = json.loads(request.content)
            requests.append(payload)
            assert payload['encoding_format'] == 'float'
            return httpx.Response(200, json={
                'object': 'list', 'model': 'text-embedding-3-small',
                'data': [{'object': 'embedding', 'index': index,
                          'embedding': [float(text.split()[-1]), 1.0]}
                         for index, text in reversed(list(enumerate(payload['input'])))],
                'usage': {'prompt_tokens': 10, 'total_tokens': 10},
            })

        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            model = OpenAIEmbeddings(model='text-embedding-3-small', api_key='test',
                                     http_async_client=client)
            vectors = await model.aembed_documents(texts)
            assert not client.is_closed
            assert await model.aembed_documents([]) == []
        assert [len(request['input']) for request in requests] == [32, 3]
        assert vectors == [[float(index), 1.0] for index in range(35)]

    asyncio.run(scenario())


@pytest.mark.parametrize('texts', [[''], ['  '], ['가' * 2667]])
def test_embedding_invalid_input_is_rejected_before_http(texts):
    model = OpenAIEmbeddings(model='text-embedding-3-small', api_key='test')
    with pytest.raises(ValueError):
        asyncio.run(model.aembed_documents(texts))


@pytest.mark.parametrize('data', [
    [], [{'index': 1, 'embedding': [1.0]}],
    [{'index': 0, 'embedding': [1.0]}, {'index': 0, 'embedding': [1.0]}],
    [{'index': 0, 'embedding': []}],
])
def test_embedding_missing_duplicate_or_invalid_vectors_fail_closed(data):
    async def scenario():
        transport = httpx.MockTransport(lambda request: httpx.Response(200, json={
            'object': 'list', 'model': 'test', 'data': data,
            'usage': {'prompt_tokens': 1, 'total_tokens': 1},
        }))
        async with httpx.AsyncClient(transport=transport) as client:
            model = OpenAIEmbeddings(model='test', api_key='test', http_async_client=client)
            with pytest.raises(ValueError):
                await model.aembed_documents(['notice'])

    asyncio.run(scenario())


def test_app_health_without_tiktoken_or_langchain_openai():
    script = '''
import importlib.abc
import sys
class BlockTokenizer(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname.split('.')[0] in {'tiktoken', 'langchain_openai'}:
            raise AssertionError('Forbidden dependency: ' + fullname)
sys.meta_path.insert(0, BlockTokenizer())
from fastapi.testclient import TestClient
from app.main import app
with TestClient(app) as client:
    response = client.get('/health')
    assert response.status_code == 200
    assert response.json() == {'status': 'UP'}
assert not any(name.split('.')[0] in {'tiktoken', 'langchain_openai'} for name in sys.modules)
print('health OK without tokenizer')
'''
    result = subprocess.run(
        [sys.executable, '-c', script], cwd=Path(__file__).resolve().parents[1],
        capture_output=True, text=True, timeout=30,
    )
    assert result.returncode == 0, result.stderr
    assert 'health OK without tokenizer' in result.stdout
