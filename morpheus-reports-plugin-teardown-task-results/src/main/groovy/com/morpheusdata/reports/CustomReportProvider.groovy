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
 * Report that list total number of users logins per month. Also includes option to list unique user logins per month
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
		'custom-report-workflow-task-results'
	}

	@Override
	String getName() {
		'Report workflow task results for an instance'
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
		
		getRenderer().renderTemplate("hbs/taskResultsReport", model)
			
		
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

			String dbQParam ="''" 
			if (reportResult.configMap?.instanceName){ 
				dbQParam ="'${reportResult.configMap?.instanceName}'"
			} 
			
			results = new Sql(dbConnection).rows("select id,instance_name,instance_id,event_title,task_id,status,output as task_output,message,error,start_date from process_event where instance_name ="+dbQParam+";")
				
			
		}

		//log.info("Results: ${results}")
		Observable<GroovyRowResult> observable = Observable.fromIterable(results) as Observable<GroovyRowResult>
		observable.map{ resultRow ->
			log.info("Mapping resultRow ${resultRow}")
			def Map<String,Object> data = [:]
			
			data = [EventId: resultRow.id.toString(),
					InstanceId: resultRow.instance_id.toString(),
					InstanceName: resultRow.instance_name,
					EventTitle: resultRow.event_title,
					TaskId: resultRow.task_id.toString(),
					Status: resultRow.status,
					TaskOutput: resultRow.task_output,
					TaskMessage: resultRow.message,
					TaskError: resultRow.error,
					StartDate: resultRow.start_date.toString()]
			
			
			
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
		 return "Report to show workflow task results for an instance"
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

	 OptionType instanceNameTextField = new OptionType(
	 										code: 'instance-name-text-field', 
	 										name: 'Instance Name', 
	 										fieldName: 'instanceName', 
	 										fieldContext: 'config', 
	 										fieldLabel: 'Instance Name', 
	 										displayOrder: 0,
	 										helpBlock:'Specify the name of the instance to get its task results'
	 										)



	 @Override
	 List<OptionType> getOptionTypes() {
		 [instanceNameTextField]
	 }
 }
