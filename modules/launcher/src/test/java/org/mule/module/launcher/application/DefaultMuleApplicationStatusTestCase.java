/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.module.launcher.application;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mule.api.MuleContext;
import org.mule.api.context.notification.MuleContextNotificationListener;
import org.mule.context.notification.MuleContextNotification;
import org.mule.context.notification.NotificationException;
import org.mule.module.launcher.artifact.ArtifactClassLoader;
import org.mule.module.launcher.descriptor.ApplicationDescriptor;
import org.mule.module.launcher.domain.Domain;
import org.mule.tck.junit4.AbstractMuleContextTestCase;
import org.mule.tck.probe.JUnitProbe;
import org.mule.tck.probe.PollingProber;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/**
 * This tests verifies that the {@link org.mule.module.launcher.application.DefaultMuleApplication}
 * status is set correctly depending on its {@link org.mule.api.MuleContext}'s lifecycle phase
 */
@RunWith(MockitoJUnitRunner.class)
public class DefaultMuleApplicationStatusTestCase extends AbstractMuleContextTestCase
{

    private static final int PROBER_TIMEOUT = 1000;
    private static final int PROBER_INTERVAL = 100;

    private DefaultMuleApplication application;

    @Mock
    private ApplicationDescriptor descriptor;

    @Override
    protected void doSetUp() throws Exception
    {
        when(descriptor.getAppName()).thenReturn(getClass().getName());

        application = new DefaultMuleApplication(descriptor, null, null);
        application.setMuleContext(muleContext);
    }

    @Test
    public void initialState()
    {
        assertStatus(ApplicationStatus.CREATED);
    }

    @Test
    public void initialised()
    {
        // the context was initialised before we gave it to the application, so we need
        // to fire the notification again since the listener wasn't there
        muleContext.fireNotification(new MuleContextNotification(muleContext, MuleContextNotification.CONTEXT_INITIALISED));
        assertStatus(ApplicationStatus.INITIALISED);
    }

    @Test
    public void started() throws Exception
    {
        muleContext.start();
        assertStatus(ApplicationStatus.STARTED);
    }

    @Test
    public void stopped() throws Exception
    {
        muleContext.start();
        muleContext.stop();
        assertStatus(ApplicationStatus.STOPPED);
    }

    @Test
    public void destroyed()
    {
        muleContext.dispose();
        assertStatus(ApplicationStatus.DESTROYED);
    }

    @Test
    public void deploymentFailedOnInit()
    {
        when(descriptor.getAppName()).thenReturn(getClass().getName());
        application = new DefaultMuleApplication(descriptor, null, null);
        try
        {
            application.init();
            fail("Was expecting init to fail");
        }
        catch (Exception e)
        {
            assertStatus(ApplicationStatus.DEPLOYMENT_FAILED);
        }
    }

    @Test
    public void deploymentFailedOnStart() throws Exception
    {
        MuleContext mockContext = mock(MuleContext.class);
        application.setMuleContext(mockContext);
        verify(mockContext).registerListener(any(MuleContextNotificationListener.class));
        doThrow(new RuntimeException()).when(mockContext).start();

        try
        {
            application.start();
            fail("Was expecting start to fail");
        }
        catch (Exception e)
        {
            verify(mockContext).unregisterListener(any(MuleContextNotificationListener.class));
            assertStatus(ApplicationStatus.DEPLOYMENT_FAILED);
        }
    }

    @Test
    public void disposesDeploymentClassLoader() throws NotificationException
    {
        ApplicationDescriptor descriptor = mock(ApplicationDescriptor.class);
        when(descriptor.getAbsoluteResourcePaths()).thenReturn(new String[] {});

        ApplicationClassLoaderFactory classLoaderFactory = mock(ApplicationClassLoaderFactory.class);
        final ArtifactClassLoader applicationClassLoader1 = mock(ApplicationClassLoader.class);
        final ArtifactClassLoader applicationClassLoader2 = mock(ApplicationClassLoader.class);
        when(classLoaderFactory.create(descriptor)).thenReturn(applicationClassLoader1).thenReturn(applicationClassLoader2);

        DefaultMuleApplication application = new DefaultMuleApplication(descriptor, classLoaderFactory, mock(Domain.class));
        application.install();

        assertThat(application.deploymentClassLoader, is(applicationClassLoader1));

        application.dispose();

        assertThat(application.deploymentClassLoader, is(nullValue()));
        assertThat(application.getArtifactClassLoader(), is(applicationClassLoader2));
    }

    private void assertStatus(final ApplicationStatus status)
    {
        PollingProber prober = new PollingProber(PROBER_TIMEOUT, PROBER_INTERVAL);
        prober.check(new JUnitProbe()
        {
            @Override
            protected boolean test() throws Exception
            {
                assertThat(application.getStatus(), is(status));
                return true;
            }

            @Override
            public String describeFailure()
            {
                return String.format("Application remained at status %s instead of moving to %s", application.getStatus().name(), status.name());
            }
        });

    }
}
