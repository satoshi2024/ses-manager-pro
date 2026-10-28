(function (global) {
    global.sesDelayIndexedDbCompletion = function (transaction, handler, onComplete) {
        transaction.addEventListener('complete', function (event) {
            onComplete(function () { handler.call(transaction, event); }, event);
        }, { once: true });
    };
})(globalThis);
