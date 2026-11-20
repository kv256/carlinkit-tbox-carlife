package android.app;

import android.content.ComponentName;
import android.os.RemoteException;

public abstract class TaskStackListener implements ITaskStackListener {
    public TaskStackListener() {}
    public void onTaskStackChanged() throws RemoteException {}
    public void onTaskMovedToFront(int taskId) throws RemoteException {}
    public void onTaskCreated(int taskId, ComponentName componentName) throws RemoteException {}
    public void onTaskRemoved(int taskId) throws RemoteException {}
    public void onActivityLaunchOnSecondaryDisplayFailed() throws RemoteException {}
}
