package android.app;

import android.os.Bundle;
import android.os.RemoteException;
import java.util.List;

public interface IActivityManager {
    List<?> getAllStackInfos() throws RemoteException;
    void moveStackToDisplay(int stackId, int displayId) throws RemoteException;
    void moveTaskToFront(int taskId, int flags, Bundle options) throws RemoteException;
    void setFocusedStack(int stackId) throws RemoteException;
    boolean removeTask(int taskId) throws RemoteException;
    void registerTaskStackListener(ITaskStackListener listener) throws RemoteException;
    void unregisterTaskStackListener(ITaskStackListener listener) throws RemoteException;
}
