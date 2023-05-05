package com.morpheusdata.reports

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportType
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.response.ServiceResponse
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.util.logging.Slf4j
import io.reactivex.Observable;

import java.sql.Connection

/**
 * Report to list total number of successful and failed instance provisions.
 * This report does not take into account user permissions currently and simply uses a direct SQL Connection using Groovy SQL with RxJava to generate a set of results.
 *
 * A renderer is also defined to render the HTML via Handlebars templates.
 *
 * @author Waqas Abbas
 */
 @Slf4j
class CustomReportProvider extends AbstractReportProvider {
	Plugin plugin
	MorpheusContext morpheusContext

	CustomReportProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	MorpheusContext getMorpheus() {
		morpheusContext
	}

	@Override
	Plugin getPlugin() {
		plugin
	}

	@Override
	String getCode() {
		'custom-report-instance-provision-failures'
	}

	@Override
	String getName() {
		'Report Morpheus Instance Provision Success/Failures'
	}

	 ServiceResponse validateOptions(Map opts) {
		 return ServiceResponse.success()
	 }

	/**
	 * Demonstrates building a TaskConfig to get details about the Instance and renders the html from the specified template.
	 * @param instance details of an Instance
	 * @return
	 */
	@Override
	HTMLResponse renderTemplate(ReportResult reportResult, Map<String, List<ReportResultRow>> reportRowsBySection) {
		ViewModel<String> model = new ViewModel<String>()
		model.object = reportRowsBySection
		getRenderer().renderTemplate("hbs/failureReport", model)
	}

	/**
	 * Allows various sources used in the template to be loaded
	 * @return
	 */
	@Override
	ContentSecurityPolicy getContentSecurityPolicy() {
		def csp = new ContentSecurityPolicy()
		csp.scriptSrc = '*.jsdelivr.net'
		csp.frameSrc = '*.digitalocean.com'
		csp.imgSrc = '*.wikimedia.org'
		csp.styleSrc = 'https: *.bootstrapcdn.com'
		csp
	}


	void process(ReportResult reportResult) {
		morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.generating).blockingGet();
		Long displayOrder = 0
		List<GroovyRowResult> results = []
		withDbConnection { Connection dbConnection ->

			String dbQParam ="'provision'" 
			if (reportResult.configMap?.includeOthersChkbx){ //Include post provision workflow execution in the report
				dbQParam ="'provision','server provision','app provision','cluster provision'"
			} 

			results = new Sql(dbConnection).rows("select YEAR(p.date_created) as year, MONTHNAME(p.date_created) as month,sum(case when status='failed' then 1 else 0 end) as total_failed,sum(case when status='complete' then 1 else 0 end) as total_successful,count(*) as total from process p where process_type_name IN("+dbQParam+") GROUP BY YEAR(p.date_created), MONTHNAME(p.date_created) ORDER BY YEAR(p.date_created) DESC,FIELD(MONTH,'January','February','March','April','May','June','July','August','September','October','November','December') DESC;")
		
		}

		//log.info("Results: ${results}")
		Observable<GroovyRowResult> observable = Observable.fromIterable(results) as Observable<GroovyRowResult>
		observable.map{ resultRow ->
			//log.info("Mapping resultRow ${resultRow}")
			Map<String,Object> data = [Year: resultRow.year, Month: resultRow.month, TotalFailed: resultRow.total_failed, TotalSuccessful: resultRow.total_successful,Total: resultRow.total ]
			ReportResultRow resultRowRecord = new ReportResultRow(section: ReportResultRow.SECTION_MAIN, displayOrder: displayOrder++, dataMap: data)
			//log.info("resultRowRecord: ${resultRowRecord.dump()}")
			return resultRowRecord
		}.buffer(50).doOnComplete {
			morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.ready).blockingGet();
		}.doOnError { Throwable t ->
			morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.failed).blockingGet();
		}.subscribe {resultRows ->
			morpheus.report.appendResultRows(reportResult,resultRows).blockingGet()
		}

	}

	 @Override
	 String getDescription() {
		 return "Report to list instance provision success and failures"
	 }

	 @Override
	 String getCategory() {
		 return 'inventory'
	 }

	 @Override
	 Boolean getOwnerOnly() {
		 return false
	 }

	 @Override
	 Boolean getMasterOnly() {
		 return true
	 }

	 @Override
	 Boolean getSupportsAllZoneTypes() {
		 return true
	 }

	 OptionType includeOthersCheckbox = new OptionType(
                                name: 'Include Apps,Cluster/Nodes',
                                code: 'post-provision-others-chkbox',
                                fieldName: 'includeOthersChkbx',
                                displayOrder: 0,
                                fieldLabel: 'Include Apps, Clusters/Nodes',
                                required: false,
                                inputType: OptionType.InputType.CHECKBOX,
                                fieldContext: 'config'
                )

	 @Override
	 List<OptionType> getOptionTypes() {
		 [includeOthersCheckbox]
	 }
 }
