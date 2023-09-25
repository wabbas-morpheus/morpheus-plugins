/**
 * alarm counts and open alarm table
 * @author wabbas
 */
class CatalogsWidget extends React.Component {

  constructor(props) {
    super(props);
    //set state
    this.state = {
      loaded: false,
      autoRefresh: true,
      data: null
    };
    //dataType
    this.state.type = 'all' //error, warnings, all?
    //apply state config
    if(props.autoRefresh == false)
      this.state.autoRefresh = false;
    //bind methods
    this.setData = this.setData.bind(this);
    this.refreshData = this.refreshData.bind(this);
  }

  componentDidMount() {
    this.loadData();
    $(document).on('morpheus:refresh', this.refreshData);
  }

  componentDidUpdate() {
    //refresh times
    Morpheus.timeService.refresh();
  }

  //data methods
  refreshData() {
    if(this.state.autoRefresh == true)
      this.loadData();
  }

  loadData() {
    //call api for data...
    // var apiQuery = '';
    // var apiOptions = { max:5 };
    // Morpheus.api.activity.recent(apiQuery, apiOptions).then(this.setData);
    fetch("/plugin/catalogs")
            .then(res => res.json())
            .then(this.setData);
  }

  setData(results) {
    //set it
    console.log("results = "+results);
    var newState = {};
    newState.data = {};
    // newState.data.config = results.config;
    // newState.data.offset = results.offset;
    //newState.data.max = results.max;
    //set the data list
    newState.data.items = results.catalogs;
    //mark it loaded
    newState.loaded = true;
    newState.data.loaded = true;
    // newState.date = Date.now();
    newState.error = false;
    newState.errorMessage = null;
    //update the state
    this.setState(newState);
  }

  render() {
    //setup
    var isLoaded = this.state.data && this.state.data.loaded == true;
    var itemList = isLoaded == true && this.state.data.items ? this.state.data.items : [];
    var showTable = isLoaded == true && itemList.length > 0;
    //render
    return (
      <Widget>
        <WidgetHeader icon="/assets/dashboard.svg#logs" title="First 10 Catalogs" link="/provisioning/catalog"/>
        <div className="dashboard-widget-content">
          <table className={'widget-table' + (showTable ? '' : ' hidden')}>
            <tbody>
              { itemList.map(row => (
                <tr key={row.id}>
                  <td className="col-md nowrap">
                    {row.id ? row.id : ''}
                  </td>
                  <td className="col-md nowrap">
                    {row.name ? row.name : ''}
                  </td>
                  <td className="col-md nowrap">
                    {row.name ? row.visibility : ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <EmptyWidget isEmpty={isLoaded == true && showTable != true}/>
          <LoadingWidget isLoading={isLoaded != true}/>
        </div>
      </Widget>
    );
  }

}

//register it
Morpheus.components.register('catalogs-widget', CatalogsWidget);

$(document).ready(function () {
  const root = ReactDOM.createRoot(document.querySelector('#catalogs-widget'));
  root.render(<CatalogsWidget/>)
});
