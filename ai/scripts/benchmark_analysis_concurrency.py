"""Measure the real analysis job with local delivery stubs and metadata-only output.

This script makes paid model/embedding calls. It never invokes the backend,
database, report generator, or notification clients. Run each arm in a fresh
process using the same AnalysisJobRequest JSON and an unused output directory.
"""

import argparse
import asyncio
import hashlib
import json
import logging
import sys
import time
from contextvars import ContextVar
from dataclasses import asdict, replace
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import httpx
from langchain_openai import ChatOpenAI

from app.domains.analysis import tasks
from app.domains.analysis.config import AnalysisSettings
from app.domains.analysis.schemas.request import AnalysisJobRequest
from app.domains.analysis.workflow.retry_policy import is_timeout_error

DOC = ContextVar("benchmark_document", default=0)
STAGE = ContextVar("benchmark_stage", default="analysis")


class Measurement:
    def __init__(self, output: Path, concurrency: int):
        output.mkdir(parents=True, exist_ok=False)
        self.output = output
        self.concurrency = concurrency
        self.started = time.perf_counter()
        self.events = []
        self.phases = {}
        self.results = []
        self.failures = []
        self.proposals = []
        self.active = 0
        self.peak_active = 0

    def save(self):
        # No prompts, source text, generated prose, credentials or exception messages.
        data = dict(
            concurrency=self.concurrency, elapsed_seconds=time.perf_counter() - self.started,
            phases=self.phases, analysis_results=self.results, failures=self.failures,
            proposals=self.proposals, peak_active_documents=self.peak_active,
            events=self.events,
        )
        self.output.joinpath("progress.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        return data

    async def timed(self, kind, method, *args, **kwargs):
        started = time.perf_counter()
        event = dict(kind=kind, document_id=DOC.get(), stage=STAGE.get(),
                     start_seconds=started - self.started)
        try:
            result = await method(*args, **kwargs)
            event["status"] = "success"
            return result
        except BaseException as error:
            event.update(status=type(error).__name__, timeout=is_timeout_error(error))
            raise
        finally:
            event["seconds"] = time.perf_counter() - started
            self.events.append(event)
            self.save()


async def measure(request: AnalysisJobRequest, settings: AnalysisSettings, output: Path):
    measurement = Measurement(output, settings.concurrency)
    original_runner = tasks.LangChainAnalysisRunner
    original_workflow = tasks.DocumentAnalysisWorkflow
    original_proposal = tasks.LangChainProposalGenerationRunner
    original_generate = ChatOpenAI._agenerate
    original_http = httpx.AsyncClient.send
    original_analyze = tasks._analyze_documents
    original_embedding = tasks._attach_similarity_embeddings
    original_proposals = tasks._generate_and_deliver_proposals

    class Runner(original_runner):
        def __init__(self, current):
            super().__init__(current)
            collect = self._evidence_agent.collect

            async def evidence(*args, **kwargs):
                token = STAGE.set("evidence")
                try:
                    return await measurement.timed("evidence", collect, *args, **kwargs)
                finally:
                    STAGE.reset(token)

            self._evidence_agent.collect = evidence

        async def analyze(self, document, feedback=None):
            return await measurement.timed(
                "analysis_attempt", super().analyze, document, feedback
            )

        async def _assess_legal_risks(self, document):
            token = STAGE.set("legal")
            try:
                return await measurement.timed(
                    "legal", super()._assess_legal_risks, document
                )
            finally:
                STAGE.reset(token)

    class Workflow(original_workflow):
        async def analyze(self, document):
            token = DOC.set(document.version_id)
            measurement.active += 1
            measurement.peak_active = max(measurement.peak_active, measurement.active)
            try:
                result = await measurement.timed("document", super().analyze, document)
                print(f"analysis {document.version_id} completed", flush=True)
                return result
            finally:
                measurement.active -= 1
                DOC.reset(token)

    class ProposalRunner(original_proposal):
        async def generate(self, document, result):
            doc_token, stage_token = DOC.set(document.version_id), STAGE.set("proposal")
            try:
                return await measurement.timed("proposal", super().generate, document, result)
            finally:
                DOC.reset(doc_token)
                STAGE.reset(stage_token)

    class LocalResults:
        def __init__(self, **kwargs):
            pass

        async def send(self, payload):
            measurement.results = [dict(
                id=r.version_id, eligibility=r.eligibility,
                proposal_status=r.proposal.draft_status,
                embedding_present=bool(r.similarity_embedding),
            ) for r in payload.results]
            measurement.failures = [dict(id=f.version_id) for f in payload.failures]
            measurement.save()
            return SimpleNamespace(data=SimpleNamespace(
                stored_analysis_count=len(payload.results), duplicate_analysis_count=0,
                failed_analysis_count=len(payload.failures),
            ))

        async def send_proposals(self, payload):
            measurement.proposals = [dict(
                id=r.version_id, status=r.proposal.draft_status,
                checklist_count=len(r.proposal.preparation.submission_documents)
                if r.proposal.preparation else 0,
            ) for r in payload.results]
            measurement.save()
            return SimpleNamespace(
                data=SimpleNamespace(updated_proposal_count=len(payload.results))
            )

    async def generate(model, *args, **kwargs):
        started = time.perf_counter()
        event = dict(kind="model", stage=STAGE.get(), document_id=DOC.get(),
                     start_seconds=started - measurement.started)
        try:
            result = await original_generate(model, *args, **kwargs)
            event.update(status="success", usage=result.generations[0].message.usage_metadata)
            return result
        except BaseException as error:
            event.update(status=type(error).__name__, timeout=is_timeout_error(error))
            raise
        finally:
            event["seconds"] = time.perf_counter() - started
            measurement.events.append(event)
            measurement.save()

    async def http(client, request, *args, **kwargs):
        # Reject unexpected destinations before sending; no backend can be contacted.
        if not request.url.path.endswith(("/chat/completions", "/embeddings", "/responses")):
            raise RuntimeError("Unexpected network operation in isolated benchmark")
        started = time.perf_counter()
        event = dict(kind="http", stage=STAGE.get(), document_id=DOC.get(),
                     start_seconds=started - measurement.started)
        try:
            response = await original_http(client, request, *args, **kwargs)
            event.update(status=response.status_code)
            if response.status_code == 429:
                event["rate_limited"] = True
            return response
        except BaseException as error:
            event.update(status=type(error).__name__, timeout=is_timeout_error(error))
            raise
        finally:
            event["seconds"] = time.perf_counter() - started
            measurement.events.append(event)
            measurement.save()

    async def phase(name, method, *args):
        token = STAGE.set(name)
        started = time.perf_counter()
        try:
            return await method(*args)
        finally:
            measurement.phases[name] = time.perf_counter() - started
            measurement.save()
            STAGE.reset(token)
            print(f"phase {name} {measurement.phases[name]:.2f}s", flush=True)

    async def analyze(*args):
        return await phase("analysis", original_analyze, *args)

    async def embedding(*args):
        return await phase("embedding", original_embedding, *args)

    async def proposals(*args):
        return await phase("proposal", original_proposals, *args)

    patches = {
        "LangChainAnalysisRunner": Runner, "DocumentAnalysisWorkflow": Workflow,
        "LangChainProposalGenerationRunner": ProposalRunner, "AnalysisResultClient": LocalResults,
        "_analyze_documents": analyze, "_attach_similarity_embeddings": embedding,
        "_generate_and_deliver_proposals": proposals,
    }
    # Production orchestration, stage barriers, retry limits and proposal gate remain intact.
    with (
        patch.multiple(tasks, **patches),
        patch.object(AnalysisSettings, "from_env", return_value=settings),
        patch.object(ChatOpenAI, "_agenerate", generate),
        patch.object(httpx.AsyncClient, "send", http),
    ):
        await tasks.run_analysis_job(uuid4(), request)
    result = measurement.save()
    result["settings"] = {k:v for k,v in asdict(settings).items() if k != "api_key"}
    result["input_sha256"] = hashlib.sha256(request.model_dump_json().encode()).hexdigest()
    result["documents"] = [dict(
        id=d.version_id, title=d.title, body_chars=len(d.content_text or ""),
        attachment_count=len(d.attachments),
        attachment_chars=sum(len(a.extracted_text or "") for a in d.attachments),
    ) for d in request.documents]
    output.joinpath("result.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"total concurrency={settings.concurrency} {result['elapsed_seconds']:.2f}s", flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--concurrency", type=int, choices=(2, 3), required=True)
    args = parser.parse_args()
    request = AnalysisJobRequest.model_validate_json(args.input.read_text(encoding="utf-8"))
    settings = replace(AnalysisSettings.from_env(), concurrency=args.concurrency)
    logging.disable(logging.CRITICAL)
    asyncio.run(measure(request, settings, args.output))


if __name__ == "__main__":
    main()
