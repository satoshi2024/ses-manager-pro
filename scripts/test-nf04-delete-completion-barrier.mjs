import assert from 'node:assert/strict';
import '../src/test/resources/js/pwa-delete-completion-barrier.js';

const transaction = new EventTarget();
let deferredResume;
let completionObserved = false;
let productionHandlerCalled = false;
let productionThisIsTransaction = false;

globalThis.sesDelayIndexedDbCompletion(transaction, function (event) {
    productionHandlerCalled = event.type === 'complete';
    productionThisIsTransaction = this === transaction;
}, (resume, event) => {
    completionObserved = event.type === 'complete';
    deferredResume = resume;
});

transaction.dispatchEvent(new Event('complete'));
assert.equal(completionObserved, true, 'completeイベントが共通helperへ届く');
assert.equal(productionHandlerCalled, false, '解放前はproduction callbackを保留する');
deferredResume();
assert.equal(productionHandlerCalled, true, '解放後にproduction callbackを呼ぶ');
assert.equal(productionThisIsTransaction, true, 'callbackのthisをtransactionに保つ');
process.stdout.write('IDB completeイベントの遅延callbackを確認しました。\n');
