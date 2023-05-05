package com.morpheusdata.reports

import com.morpheusdata.core.Plugin

/**
 * Example Custom Reports Plugin
 */
class ReportsPlugin extends Plugin {

	@Override
	String getCode() {
		return 'morpheus-reports-plugin-user-logins'
	}

	@Override
	void initialize() {
		CustomReportProvider customReportProvider = new CustomReportProvider(this, morpheus)
		this.pluginProviders.put(customReportProvider.code, customReportProvider)
		this.setName("Custom Reports - Report Morpheus User Logins Per Month")
		
	}

	@Override
	void onDestroy() {
	}
}
