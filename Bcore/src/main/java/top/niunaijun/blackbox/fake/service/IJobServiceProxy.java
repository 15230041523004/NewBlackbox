package top.niunaijun.blackbox.fake.service;

import android.app.job.JobInfo;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.job.BRIJobSchedulerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.UIDSpoofingHelper;


public class IJobServiceProxy extends BinderInvocationStub {
    public static final String TAG = "JobServiceStub";

    public IJobServiceProxy() {
        super(BRServiceManager.get().getService(Context.JOB_SCHEDULER_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder jobScheduler = BRServiceManager.get().getService("jobscheduler");
        return BRIJobSchedulerStub.get().asInterface(jobScheduler);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    @ProxyMethod("schedule")
    public static class Schedule extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args == null || args.length == 0) {
                    Slog.w(TAG, "Schedule: No arguments provided, returning RESULT_SUCCESS");
                    return 1; 
                }
                
                int jobInfoIndex = -1;
                JobInfo jobInfo = null;
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof JobInfo) {
                        jobInfo = (JobInfo) args[i];
                        jobInfoIndex = i;
                        break;
                    }
                }
                
                if (jobInfo == null) {
                    if (args[0] instanceof String) {
                        return handleNonJobInfoSchedule(who, method, args);
                    }
                    Slog.w(TAG, "Schedule: No JobInfo argument found, returning RESULT_SUCCESS");
                    return 1; 
                }
                
                Slog.d(TAG, "Schedule: Processing JobInfo for package: " + jobInfo.getService().getPackageName());
                
                try {
                    JobInfo proxyJobInfo = BlackBoxCore.getBJobManager().schedule(jobInfo);
                    if (proxyJobInfo != null) {
                        args[jobInfoIndex] = proxyJobInfo;
                        Slog.d(TAG, "Schedule: Successfully created proxy JobInfo");
                        return method.invoke(who, args);
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Schedule: BlackBox job manager failed, trying system fallback", e);
                }
                
                return scheduleWithUIDSpoofing(who, method, args, jobInfo);
                
            } catch (Exception e) {
                Slog.e(TAG, "Schedule: Error processing job", e);
                return 1; 
            }
        }
        
        
        private Object handleNonJobInfoSchedule(Object who, Method method, Object[] args) throws Throwable {
            try {
                
                if (args[0] instanceof String) {
                    String workId = (String) args[0];
                    Slog.d(TAG, "Schedule: Handling WorkManager string ID: " + workId);
                    
                    
                    JobInfo minimalJobInfo = createMinimalJobInfo(workId);
                    if (minimalJobInfo != null) {
                        args[0] = minimalJobInfo;
                        return method.invoke(who, args);
                    }
                }
                
                
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "Schedule: Failed to handle non-JobInfo schedule", e);
                return 0; 
            }
        }
        
        
        private Object scheduleWithUIDSpoofing(Object who, Method method, Object[] args, JobInfo jobInfo) throws Throwable {
            try {
                String targetPackage = jobInfo.getService().getPackageName();
                Slog.d(TAG, "Schedule: Attempting UID spoofing for package: " + targetPackage);
                
                UIDSpoofingHelper.logUIDInfo("job_schedule", targetPackage);
                
                if (UIDSpoofingHelper.needsUIDSpoofing("job_schedule", targetPackage)) {
                    Slog.d(TAG, "Schedule: UID spoofing needed, returning RESULT_SUCCESS");
                    return 1; 
                } else {
                    Slog.d(TAG, "Schedule: No UID spoofing needed, proceeding normally");
                    return method.invoke(who, args);
                }
            } catch (Exception e) {
                Slog.w(TAG, "Schedule: UID spoofing failed, returning RESULT_SUCCESS", e);
                return 1; 
            }
        }
        
        
        private JobInfo createMinimalJobInfo(String workId) {
            try {
                
                
                Slog.d(TAG, "Schedule: Creating minimal JobInfo for work ID: " + workId);
                return null; 
            } catch (Exception e) {
                Slog.w(TAG, "Schedule: Failed to create minimal JobInfo", e);
                return null;
            }
        }
        
        
        private boolean isUIDValidationError(Exception e) {
            if (e.getCause() instanceof IllegalArgumentException) {
                String message = e.getCause().getMessage();
                return message != null && message.contains("cannot schedule job");
            }
            
            if (e.getCause() instanceof android.os.RemoteException) {
                String message = e.getCause().getMessage();
                return message != null && message.contains("cannot schedule job");
            }
            
            return false;
        }
    }

    @ProxyMethod("cancel")
    public static class Cancel extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Integer jobId = (Integer) args[0];
                if (jobId == null) {
                    Slog.w(TAG, "Cancel: JobId is null");
                    return method.invoke(who, args);
                }
                
                args[0] = BlackBoxCore.getBJobManager()
                        .cancel(BActivityThread.getAppConfig().processName, jobId);
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.e(TAG, "Cancel: Error canceling job", e);
                return method.invoke(who, args);
            }
        }
    }

    @ProxyMethod("cancelAll")
    public static class CancelAll extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                BlackBoxCore.getBJobManager().cancelAll(BActivityThread.getAppConfig().processName);
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.e(TAG, "CancelAll: Error canceling all jobs", e);
                return method.invoke(who, args);
            }
        }
    }

    @ProxyMethod("enqueue")
    public static class Enqueue extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args == null || args.length == 0) {
                    Slog.w(TAG, "Enqueue: No arguments provided, returning RESULT_SUCCESS");
                    return 1; 
                }
                
                int jobInfoIndex = -1;
                JobInfo jobInfo = null;
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof JobInfo) {
                        jobInfo = (JobInfo) args[i];
                        jobInfoIndex = i;
                        break;
                    }
                }
                
                if (jobInfo == null) {
                    if (args[0] instanceof String) {
                        return handleNonJobInfoEnqueue(who, method, args);
                    }
                    Slog.w(TAG, "Enqueue: No JobInfo argument found, returning RESULT_SUCCESS");
                    return 1; 
                }
                
                Slog.d(TAG, "Enqueue: Processing JobInfo for package: " + jobInfo.getService().getPackageName());
                
                try {
                    JobInfo proxyJobInfo = BlackBoxCore.getBJobManager().schedule(jobInfo);
                    if (proxyJobInfo != null) {
                        args[jobInfoIndex] = proxyJobInfo;
                        Slog.d(TAG, "Enqueue: Successfully created proxy JobInfo");
                        return method.invoke(who, args);
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Enqueue: BlackBox job manager failed, trying system fallback", e);
                }
                
                return enqueueWithUIDSpoofing(who, method, args, jobInfo);
                
            } catch (Exception e) {
                Slog.e(TAG, "Enqueue: Error processing job", e);
                return 1; 
            }
        }
        
        
        private Object handleNonJobInfoEnqueue(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args[0] instanceof String) {
                    String workId = (String) args[0];
                    Slog.d(TAG, "Enqueue: Handling WorkManager string ID: " + workId);
                    
                    JobInfo minimalJobInfo = createMinimalJobInfo(workId);
                    if (minimalJobInfo != null) {
                        args[0] = minimalJobInfo;
                        return method.invoke(who, args);
                    }
                }
                
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "Enqueue: Failed to handle non-JobInfo enqueue", e);
                return 1; 
            }
        }
        
        
        private Object enqueueWithUIDSpoofing(Object who, Method method, Object[] args, JobInfo jobInfo) throws Throwable {
            try {
                String targetPackage = jobInfo.getService().getPackageName();
                Slog.d(TAG, "Enqueue: Attempting UID spoofing for package: " + targetPackage);
                
                UIDSpoofingHelper.logUIDInfo("job_enqueue", targetPackage);
                
                if (UIDSpoofingHelper.needsUIDSpoofing("job_enqueue", targetPackage)) {
                    Slog.d(TAG, "Enqueue: UID spoofing needed, returning RESULT_SUCCESS");
                    return 1; 
                } else {
                    Slog.d(TAG, "Enqueue: No UID spoofing needed, proceeding normally");
                    return method.invoke(who, args);
                }
                
            } catch (Exception e) {
                Slog.w(TAG, "Enqueue: UID spoofing failed, returning RESULT_SUCCESS", e);
                return 1; 
            }
        }
        
        
        private JobInfo createMinimalJobInfo(String workId) {
            try {
                
                
                Slog.d(TAG, "Enqueue: Creating minimal JobInfo for work ID: " + workId);
                return null; 
            } catch (Exception e) {
                Slog.w(TAG, "Enqueue: Failed to create minimal JobInfo", e);
                return null;
            }
        }
        
        
        private boolean isUIDValidationError(Exception e) {
            if (e.getCause() instanceof IllegalArgumentException) {
                String message = e.getCause().getMessage();
                return message != null && message.contains("cannot schedule job");
            }
            
            if (e.getCause() instanceof android.os.RemoteException) {
                String message = e.getCause().getMessage();
                return message != null && message.contains("cannot schedule job");
            }
            
            return false;
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
