/*
 * Copyright 2015 Amazon Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://aws.amazon.com/apache2.0
 *
 * This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amazonaws.eclipse.core.mobileanalytics.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.amazonaws.eclipse.core.AwsToolkitCore;
import com.amazonaws.eclipse.core.preferences.PreferenceConstants;
import com.amazonaws.eclipse.core.ui.preferences.AwsToolkitPreferencePage;

public class ToolkitAnalyticsPreferencePage extends AwsToolkitPreferencePage
        implements IWorkbenchPreferencePage {

    private Button analyticsEnabledButton;

    public ToolkitAnalyticsPreferencePage() {
        super("Anonymous Collection of Analytics");
        setPreferenceStore(AwsToolkitCore.getDefault().getPreferenceStore());
        setDescription("Anonymous Collection of Analytics");
    }

    public void init(IWorkbench workbench) {
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite composite = new Composite(parent, SWT.LEFT);
        GridLayout layout = new GridLayout();
        layout.marginWidth = 10;
        layout.marginHeight = 8;
        composite.setLayout(layout);
        GridData gridData = new GridData(GridData.FILL_HORIZONTAL);
        gridData.widthHint = 300;
        composite.setLayoutData(gridData);

        analyticsEnabledButton = new Button(composite, SWT.CHECK | SWT.MULTI | SWT.WRAP);
        analyticsEnabledButton.setText(
                "By leaving this box checked, you agree that AWS may anonymously " +
                "collect analytics about your usage of AWS Toolkit. AWS will handle " +
                "all information received in accordance with the AWS Privacy Policy " +
                "(http://aws.amazon.com/privacy/)");
        analyticsEnabledButton.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        analyticsEnabledButton.setSelection(getPreferenceStore().getBoolean(
                PreferenceConstants.P_TOOLKIT_ANALYTICS_COLLECTION_ENABLED));

        return composite;
    }

    @Override
    protected void performDefaults() {
        analyticsEnabledButton.setSelection(
                getPreferenceStore().getDefaultBoolean(
                        PreferenceConstants.P_TOOLKIT_ANALYTICS_COLLECTION_ENABLED));
    }

    @Override
    public boolean performOk() {
        boolean enabled = analyticsEnabledButton.getSelection();
        getPreferenceStore().setValue(
                PreferenceConstants.P_TOOLKIT_ANALYTICS_COLLECTION_ENABLED,
                enabled);
        AwsToolkitCore.getDefault().getAnalyticsManager().setEnabled(enabled);
        return super.performOk();
    }
}
