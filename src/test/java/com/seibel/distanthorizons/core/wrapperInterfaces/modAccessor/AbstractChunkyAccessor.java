package com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor;

/** Minimal fixture for DH 3.3.4's setup state machine, transformed by the real mixin. */
public abstract class AbstractChunkyAccessor {
    private boolean listenerBound;

    public void tryRunFirstTimeSetup() {
        if (listenerBound) return;
        listenerBound = true;
        bindOnGenerationProgressEvent();
    }

    protected abstract void bindOnGenerationProgressEvent();
}
